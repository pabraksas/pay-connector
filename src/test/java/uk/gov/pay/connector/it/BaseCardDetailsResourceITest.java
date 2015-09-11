package uk.gov.pay.connector.it;

import com.google.gson.JsonObject;
import com.jayway.restassured.specification.RequestSpecification;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.Before;
import org.junit.Rule;
import uk.gov.pay.connector.util.DropwizardAppWithPostgresRule;

import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.is;
import static uk.gov.pay.connector.model.ChargeStatus.CREATED;
import static uk.gov.pay.connector.util.JsonEncoder.toJson;

public class BaseCardDetailsResourceITest {
    @Rule
    public DropwizardAppWithPostgresRule app = new DropwizardAppWithPostgresRule();
    protected final String accountId;
    private final String paymentProvider;

    public BaseCardDetailsResourceITest(String paymentProvider) {
        this.paymentProvider = paymentProvider;
        accountId = String.valueOf(RandomUtils.nextInt(99999));
    }

    @Before
    public void setupGatewayAccount() {
        app.getDatabaseTestHelper().addGatewayAccount(accountId, paymentProvider);
    }

    protected String cardUrlFor(String id) {
        return "/v1/frontend/charges/" + id + "/cards";
    }

    protected String buildJsonCardDetailsFor(String cardNumber) {
        return buildJsonCardDetailsFor(cardNumber, "123", "11/99");
    }

    protected String buildJsonCardDetailsFor(String cardNumber, String cvc, String expiryDate) {
        return buildJsonCardDetailsFor(cardNumber, cvc, expiryDate, null, null, null, null);
    }

    protected void assertChargeStatusIs(String uniqueChargeId, String status) {
        given().port(app.getLocalPort())
                .get("/v1/frontend/charges/" + uniqueChargeId)
                .then()
                .body("status", is(status));
    }

    protected String createNewCharge() {
        String chargeId = String.valueOf(RandomUtils.nextInt(99999999));
        app.getDatabaseTestHelper().addCharge(chargeId, accountId, 500, CREATED, "returnUrl");
        return chargeId;
    }

    protected RequestSpecification givenSetup() {
        return given().port(app.getLocalPort())
                .contentType(JSON);
    }

    protected String buildJsonCardDetailsWithFullAddress() {
        return buildJsonCardDetailsFor(
                "4242424242424242",
                "123",
                "11/99",
                "Moneybags Avenue",
                "Some borough",
                "London",
                "Greater London"
        );
    }

    private String buildJsonCardDetailsFor(String cardNumber, String cvc, String expiryDate, String line2, String line3, String city, String county) {
        JsonObject addressObject = new JsonObject();

        addressObject.addProperty("line1", "The Money Pool");
        addressObject.addProperty("line2", line2);
        addressObject.addProperty("line3", line3);
        addressObject.addProperty("city", city);
        addressObject.addProperty("county", county);
        addressObject.addProperty("postcode", "DO11 4RS");
        addressObject.addProperty("country", "GB");

        JsonObject cardDetails = new JsonObject();
        cardDetails.addProperty("card_number", cardNumber);
        cardDetails.addProperty("cvc", cvc);
        cardDetails.addProperty("expiry_date", expiryDate);
        cardDetails.addProperty("cardholder_name", "Scrooge McDuck");
        cardDetails.add("address", addressObject);
        return toJson(cardDetails);
    }
}

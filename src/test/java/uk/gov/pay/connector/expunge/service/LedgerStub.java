package uk.gov.pay.connector.expunge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import uk.gov.pay.connector.charge.model.ChargeResponse;
import uk.gov.pay.connector.it.dao.DatabaseFixtures;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class LedgerStub {

    public void returnLedgerTransaction(String externalId, DatabaseFixtures.TestCharge testCharge, DatabaseFixtures.TestFee testFee) throws JsonProcessingException {
        Map<String, Object> ledgerTransactionFields = testChargeToLedgerTransactionJson(testCharge, testFee);
        stubResponse(externalId, ledgerTransactionFields);
    }

    public void returnLedgerTransactionWithMismatch(String externalId, DatabaseFixtures.TestCharge testCharge, DatabaseFixtures.TestFee testFee) throws JsonProcessingException {
        Map<String, Object> ledgerTransactionFields = testChargeToLedgerTransactionJson(testCharge, testFee);
        ledgerTransactionFields.put("description", "This is a mismatch");
        stubResponse(externalId, ledgerTransactionFields);
    }

    public void returnNotFoundForFindByProviderAndGatewayTransactionId(String paymentProvider,
                                                                       String gatewayTransactionId) throws JsonProcessingException {
        stubResponseForProviderAndGatewayTransactionId(gatewayTransactionId, paymentProvider,
                null, 404);
    }

    public void returnLedgerTransactionForProviderAndGatewayTransactionId(DatabaseFixtures.TestCharge testCharge,
                                                                          String paymentProvider) throws JsonProcessingException {
        Map<String, Object> ledgerTransactionFields = testChargeToLedgerTransactionJson(testCharge, null);
        String body = null;

        new ObjectMapper().writeValueAsString(ledgerTransactionFields);

        stubResponseForProviderAndGatewayTransactionId(testCharge.getTransactionId(),
                paymentProvider,
                ledgerTransactionFields, 200);
    }

    private void stubResponseForProviderAndGatewayTransactionId(String gatewayTransactionId, String paymentProvider,
                                                                Map<String, Object> ledgerTransactionFields, int status) throws JsonProcessingException {
        ResponseDefinitionBuilder responseDefBuilder = aResponse()
                .withHeader(CONTENT_TYPE, APPLICATION_JSON)
                .withStatus(status);

        if (nonNull(ledgerTransactionFields) && ledgerTransactionFields.size() > 0) {
            responseDefBuilder.withBody(new ObjectMapper().writeValueAsString(ledgerTransactionFields));
        }
        stubFor(
                get(urlPathEqualTo(format("/v1/transaction/gateway-transaction/%s", gatewayTransactionId)))
                        .withQueryParam("payment_provider", equalTo(paymentProvider))
                        .willReturn(
                                responseDefBuilder
                        )
        );
    }

    private void stubResponse(String externalId, Map<String, Object> ledgerTransactionFields) throws JsonProcessingException {
        ResponseDefinitionBuilder responseDefBuilder = aResponse()
                .withHeader(CONTENT_TYPE, APPLICATION_JSON)
                .withStatus(200)
                .withBody(new ObjectMapper().writeValueAsString(ledgerTransactionFields));
        stubFor(
                get(urlPathEqualTo(format("/v1/transaction/%s", externalId)))
                        .withQueryParam("override_account_id_restriction", equalTo("true"))
                        .willReturn(
                                responseDefBuilder
                        )
        );
    }

    private static Map<String, Object> testChargeToLedgerTransactionJson(DatabaseFixtures.TestCharge testCharge, DatabaseFixtures.TestFee fee) {
        var map = new HashMap<String, Object>();
        Optional.ofNullable(testCharge.getExternalChargeId()).ifPresent(value -> map.put("id", value));
        Optional.of(testCharge.getAmount()).ifPresent(value -> map.put("amount", String.valueOf(value)));
        Optional.of(testCharge.getAmount()).ifPresent(value -> map.put("total_amount", String.valueOf(value)));
        Optional.ofNullable(testCharge.getCorporateCardSurcharge()).ifPresent(value -> map.put("corporate_card_surcharge",
                String.valueOf(value)));
        Optional.ofNullable(testCharge.getChargeStatus()).ifPresent(value -> map.put("state",
                value.toExternal().getStatusV2()));
        Optional.ofNullable(testCharge.getDescription()).ifPresent(value -> map.put("description",
                value));
        Optional.ofNullable(testCharge.getReference()).ifPresent(value -> map.put("reference",
                String.valueOf(value)));
        Optional.ofNullable(testCharge.getLanguage()).ifPresent(value -> map.put("language",
                String.valueOf(value)));
        Optional.ofNullable(testCharge.getExternalChargeId()).ifPresent(value -> map.put("transaction_id",
                value));
        Optional.ofNullable(testCharge.getReturnUrl()).ifPresent(value -> map.put("return_url",
                value));
        Optional.ofNullable(testCharge.getEmail()).ifPresent(value -> map.put("email",
                value));
        Optional.ofNullable(testCharge.getCreatedDate()).ifPresent(value -> map.put("created_date",
                String.valueOf(value)));
        Optional.ofNullable(testCharge.getCardDetails()).ifPresent(value -> map.put("card_details",
                String.valueOf(value)));
        Optional.ofNullable(testCharge.getTransactionId()).ifPresent(value -> map.put("gateway_transaction_id",
                value));
        Optional.ofNullable(testCharge.getTestAccount().getAccountId()).ifPresent(account -> map.put("gateway_account_id",
                account));
        Optional.ofNullable(testCharge.getTestAccount().getPaymentProvider()).ifPresent(account -> map.put("payment_provider",
                account));
        if(fee != null) {
            map.put("fee", 0);
            Optional.of(testCharge.getAmount()).ifPresent(amount -> map.put("net_amount", amount));
            Optional.of(testCharge.getAmount()).ifPresent(amount -> map.put("total_amount", amount));
        }

        ChargeResponse.RefundSummary refundSummary = new ChargeResponse.RefundSummary();
        refundSummary.setStatus("available");
        Optional.ofNullable(testCharge.getTestAccount().getPaymentProvider()).ifPresent(account -> map.put("refund_summary",
                refundSummary));

        map.put("live", false);
        return map;
    }

}

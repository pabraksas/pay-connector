package uk.gov.pay.connector.gateway.stripe;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.setup.Environment;
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.app.LinksConfig;
import uk.gov.pay.connector.app.StripeAuthTokens;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.common.model.domain.Address;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.GatewayClientFactory;
import uk.gov.pay.connector.gateway.GatewayException.GatewayConnectionTimeoutException;
import uk.gov.pay.connector.gateway.GatewayException.GatewayErrorException;
import uk.gov.pay.connector.gateway.model.Auth3dsDetails;
import uk.gov.pay.connector.gateway.model.AuthCardDetails;
import uk.gov.pay.connector.gateway.model.request.Auth3dsResponseGatewayRequest;
import uk.gov.pay.connector.gateway.model.request.CardAuthorisationGatewayRequest;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse;
import uk.gov.pay.connector.gateway.model.response.Gateway3DSAuthorisationResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.gateway.stripe.request.StripeAuthoriseRequest;
import uk.gov.pay.connector.gateway.stripe.request.StripePaymentIntentRequest;
import uk.gov.pay.connector.gateway.stripe.request.StripePaymentMethodRequest;
import uk.gov.pay.connector.gateway.stripe.response.StripeParamsFor3ds;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.model.domain.AuthCardDetailsFixture;
import uk.gov.pay.connector.util.JsonObjectMapper;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.STRIPE;
import static uk.gov.pay.connector.gateway.model.ErrorType.GATEWAY_CONNECTION_TIMEOUT_ERROR;
import static uk.gov.pay.connector.gateway.model.ErrorType.GATEWAY_ERROR;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity.Type.TEST;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_ERROR_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_PAYMENT_INTENT_REQUIRES_3DS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_PAYMENT_METHOD_SUCCESS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.load;

public class StripePaymentProviderTest {

    private StripePaymentProvider provider;
    private GatewayClient gatewayClient = mock(GatewayClient.class);
    private GatewayClientFactory gatewayClientFactory = mock(GatewayClientFactory.class);
    private Environment environment = mock(Environment.class);
    private MetricRegistry metricRegistry = mock(MetricRegistry.class);
    private ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
    private StripeGatewayConfig gatewayConfig = mock(StripeGatewayConfig.class);
    private LinksConfig linksConfig = mock(LinksConfig.class);
    private JsonObjectMapper objectMapper = new JsonObjectMapper(new ObjectMapper());
    private GatewayClient.Response paymentMethodResponse = mock(GatewayClient.Response.class);
    private GatewayClient.Response paymentIntentsResponse = mock(GatewayClient.Response.class);

    @Before
    public void before() {
        when(gatewayConfig.getUrl()).thenReturn("http://stripe.url");
        when(gatewayConfig.getAuthTokens()).thenReturn(mock(StripeAuthTokens.class));
        when(configuration.getStripeConfig()).thenReturn(gatewayConfig);

        when(configuration.getLinks()).thenReturn(linksConfig);
        when(linksConfig.getFrontendUrl()).thenReturn("http://frontendUrl");

        when(gatewayClientFactory.createGatewayClient(eq(STRIPE), any(MetricRegistry.class))).thenReturn(gatewayClient);

        when(environment.metrics()).thenReturn(metricRegistry);

        provider = new StripePaymentProvider(gatewayClientFactory, configuration, objectMapper, environment);

        when(paymentMethodResponse.getEntity()).thenReturn(successCreatePaymentMethodResponse());
        when(paymentIntentsResponse.getEntity()).thenReturn(successCreatePaymentIntentsResponse());
    }

    @Test
    public void shouldGetPaymentProviderName() {
        assertThat(provider.getPaymentGatewayName().getName(), is("stripe"));
    }

    @Test
    public void shouldGenerateNoTransactionId() {
        assertThat(provider.generateTransactionId().isPresent(), is(false));
    }


    @Test
    public void shouldAuthoriseImmediately_whenPaymentIntentReturnsAsRequiresCapture() throws Exception {

        when(gatewayClient.postRequestFor(any(StripePaymentMethodRequest.class))).thenReturn(paymentMethodResponse);

        when(gatewayClient.postRequestFor(any(StripePaymentIntentRequest.class))).thenReturn(paymentIntentsResponse);


        GatewayAccountEntity gatewayAccount = buildTestGatewayAccountEntity();
        gatewayAccount.setIntegrationVersion3ds(2);
        GatewayResponse<BaseAuthoriseResponse> response = provider.authorise(buildTestAuthorisationRequest(gatewayAccount));

        assertThat(response.getBaseResponse().get().authoriseStatus(), is(BaseAuthoriseResponse.AuthoriseStatus.AUTHORISED));
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getTransactionId(), is("pi_1FHESeEZsufgnuO08A2FUSPy"));
    }


    @Test
    public void shouldSetAs3DSRequired_whenPaymentIntentReturnsWithRequiresAction() throws Exception {
        when(paymentIntentsResponse.getEntity()).thenReturn(requires3DSCreatePaymentIntentsResponse());
        when(gatewayClient.postRequestFor(any(StripePaymentMethodRequest.class))).thenReturn(paymentMethodResponse);

        when(gatewayClient.postRequestFor(any(StripePaymentIntentRequest.class))).thenReturn(paymentIntentsResponse);


        GatewayAccountEntity gatewayAccount = buildTestGatewayAccountEntity();
        gatewayAccount.setIntegrationVersion3ds(2);
        GatewayResponse<BaseAuthoriseResponse> response = provider.authorise(buildTestAuthorisationRequest(gatewayAccount));

        assertThat(response.getBaseResponse().get().authoriseStatus(), is(BaseAuthoriseResponse.AuthoriseStatus.REQUIRES_3DS));
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getTransactionId(), is("pi_123")); // id from templates/stripe/create_3ds_sources_response.json

        Optional<StripeParamsFor3ds> stripeParamsFor3ds = (Optional<StripeParamsFor3ds>) response.getBaseResponse().get().getGatewayParamsFor3ds();
        assertThat(stripeParamsFor3ds.isPresent(), is(true));
        assertThat(stripeParamsFor3ds.get().toAuth3dsDetailsEntity().getIssuerUrl(), containsString("https://hooks.stripe.com"));
    }


    @Test
    public void shouldNotAuthorise_whenProcessingExceptionIsThrown() throws Exception {

        when(gatewayClient.postRequestFor(any(StripePaymentMethodRequest.class))).thenReturn(paymentMethodResponse);
        when(gatewayClient.postRequestFor(any(StripePaymentIntentRequest.class)))
                .thenThrow(new GatewayConnectionTimeoutException("javax.ws.rs.ProcessingException: java.io.IOException"));

        GatewayResponse<BaseAuthoriseResponse> authoriseResponse = provider.authorise(buildTestAuthorisationRequest());

        assertThat(authoriseResponse.isFailed(), is(true));
        assertThat(authoriseResponse.getGatewayError().isPresent(), is(true));
        assertEquals("javax.ws.rs.ProcessingException: java.io.IOException",
                authoriseResponse.getGatewayError().get().getMessage());
        assertEquals(GATEWAY_CONNECTION_TIMEOUT_ERROR, authoriseResponse.getGatewayError().get().getErrorType());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrow_IfTryingToAuthoriseAnApplePayPayment() {
        provider.authoriseWallet(null);
    }

    @Test
    public void shouldNotAuthorise_whenPaymentProviderReturnsUnexpectedStatusCode() throws Exception {
        when(gatewayClient.postRequestFor(any(StripePaymentMethodRequest.class))).thenReturn(paymentMethodResponse);
        when(gatewayClient.postRequestFor(any(StripePaymentIntentRequest.class)))
                .thenThrow(new GatewayErrorException("server error", errorResponse(), 500));
        GatewayResponse<BaseAuthoriseResponse> authoriseResponse = provider.authorise(buildTestAuthorisationRequest());

        assertThat(authoriseResponse.isFailed(), is(true));
        assertThat(authoriseResponse.getGatewayError().isPresent(), is(true));
        assertThat(authoriseResponse.getGatewayError().get().getMessage(),
                containsString("There was an internal server error"));
        assertThat(authoriseResponse.getGatewayError().get().getErrorType(), is(GATEWAY_ERROR));
    }

    @Test
    public void shouldReject3DSCharge_when3DSAuthDetailsStatusIsRejected() {
        Auth3dsResponseGatewayRequest request
                = build3dsResponseGatewayRequest(Auth3dsDetails.Auth3dsResult.DECLINED);

        Gateway3DSAuthorisationResponse response = provider.authorise3dsResponse(request);

        assertThat(response.isSuccessful(), is(false));
        assertThat(response.getMappedChargeStatus(), is(ChargeStatus.AUTHORISATION_REJECTED));
    }

    @Test
    public void shouldCancel3DSCharge_when3DSAuthDetailsStatusIsCanceled() {
        Auth3dsResponseGatewayRequest request
                = build3dsResponseGatewayRequest(Auth3dsDetails.Auth3dsResult.CANCELED);

        Gateway3DSAuthorisationResponse response = provider.authorise3dsResponse(request);

        assertThat(response.getMappedChargeStatus(), is(ChargeStatus.AUTHORISATION_CANCELLED));
    }

    @Test
    public void shouldMark3DSChargeAsError_when3DSAuthDetailsStatusIsError() {
        Auth3dsResponseGatewayRequest request
                = build3dsResponseGatewayRequest(Auth3dsDetails.Auth3dsResult.ERROR);

        Gateway3DSAuthorisationResponse response = provider.authorise3dsResponse(request);

        assertThat(response.getMappedChargeStatus(), is(ChargeStatus.AUTHORISATION_ERROR));
    }

    @Test
    public void shouldMark3DSChargeAsRejected_whenGatewayOperationResultedIn4xxHttpStatus() throws Exception {
        Auth3dsResponseGatewayRequest request = build3dsResponseGatewayRequest(Auth3dsDetails.Auth3dsResult.AUTHORISED);

        when(gatewayClient.postRequestFor(any(StripeAuthoriseRequest.class)))
                .thenThrow(new GatewayErrorException("Unexpected HTTP status code 403 from gateway", errorResponse(), HttpStatus.SC_FORBIDDEN));
        Gateway3DSAuthorisationResponse response = provider.authorise3dsResponse(request);

        assertThat(response.getMappedChargeStatus(), is(ChargeStatus.AUTHORISATION_REJECTED));
    }

    @Test
    public void shouldKeep3DSChargeInAuthReadyState_when3DSAuthDetailsAreNotAvailable() {
        Auth3dsResponseGatewayRequest request
                = build3dsResponseGatewayRequest(null);

        Gateway3DSAuthorisationResponse response = provider.authorise3dsResponse(request);

        assertTrue(response.isSuccessful());
        assertThat(response.getMappedChargeStatus(), is(ChargeStatus.AUTHORISATION_3DS_READY));
    }

    private Auth3dsResponseGatewayRequest build3dsResponseGatewayRequest(Auth3dsDetails.Auth3dsResult auth3dsResult) {
        Auth3dsDetails auth3dsDetails = new Auth3dsDetails();
        if (auth3dsResult != null) {
            auth3dsDetails.setAuth3dsResult(auth3dsResult.toString());
        }
        ChargeEntity chargeEntity = buildTestCharge();

        return new Auth3dsResponseGatewayRequest(chargeEntity, auth3dsDetails);
    }

    private String successCreatePaymentMethodResponse() {
        return load(STRIPE_PAYMENT_METHOD_SUCCESS_RESPONSE);
    }

    private String successCreatePaymentIntentsResponse() {
        return load(STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE);
    }

    private String requires3DSCreatePaymentIntentsResponse() {
        return load(STRIPE_PAYMENT_INTENT_REQUIRES_3DS_RESPONSE);
    }

    private String errorResponse() {
        return load(STRIPE_ERROR_RESPONSE);
    }

    private CardAuthorisationGatewayRequest buildTestAuthorisationRequest() {
        return buildTestAuthorisationRequest(buildTestGatewayAccountEntity());
    }


    private CardAuthorisationGatewayRequest buildTestAuthorisationRequest(GatewayAccountEntity accountEntity) {
        ChargeEntity chargeEntity = aValidChargeEntity()
                .withExternalId("mq4ht90j2oir6am585afk58kml")
                .withGatewayAccountEntity(accountEntity)
                .build();
        return buildTestAuthorisationRequest(chargeEntity);
    }

    private ChargeEntity buildTestCharge() {
        ChargeEntity mq4ht90j2oir6am585afk58kml = aValidChargeEntity()
                .withExternalId("mq4ht90j2oir6am585afk58kml")
                .withTransactionId("transaction-id")
                .withGatewayAccountEntity(buildTestGatewayAccountEntity())
                .build();
        return mq4ht90j2oir6am585afk58kml;
    }

    private CardAuthorisationGatewayRequest buildTestAuthorisationRequest(ChargeEntity chargeEntity) {
        return new CardAuthorisationGatewayRequest(chargeEntity, buildTestAuthCardDetails());
    }

    private AuthCardDetails buildTestAuthCardDetails() {
        Address address = new Address("10", "Wxx", "E1 8xx", "London", null, "GB");
        return AuthCardDetailsFixture.anAuthCardDetails()
                .withCardHolder("Mr. Payment")
                .withCardNo("4242424242424242")
                .withCvc("111")
                .withEndDate("08/99")
                .withCardBrand("visa")
                .withAddress(address)
                .build();
    }

    private GatewayAccountEntity buildTestGatewayAccountEntity() {
        GatewayAccountEntity gatewayAccount = new GatewayAccountEntity();
        gatewayAccount.setId(1L);
        gatewayAccount.setGatewayName("stripe");
        gatewayAccount.setRequires3ds(false);
        gatewayAccount.setCredentials(ImmutableMap.of("stripe_account_id", "stripe_account_id"));
        gatewayAccount.setType(TEST);
        return gatewayAccount;
    }
}

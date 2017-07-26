package uk.gov.pay.connector.it.contract;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import uk.gov.pay.connector.model.CancelGatewayRequest;
import uk.gov.pay.connector.model.CaptureGatewayRequest;
import uk.gov.pay.connector.model.RefundGatewayRequest;
import uk.gov.pay.connector.model.domain.*;
import uk.gov.pay.connector.model.gateway.AuthorisationGatewayRequest;
import uk.gov.pay.connector.model.gateway.GatewayResponse;
import uk.gov.pay.connector.service.GatewayClient;
import uk.gov.pay.connector.service.GatewayOperation;
import uk.gov.pay.connector.service.GatewayOperationClientBuilder;
import uk.gov.pay.connector.service.PaymentProvider;
import uk.gov.pay.connector.service.epdq.EpdqAuthorisationResponse;
import uk.gov.pay.connector.service.epdq.EpdqCaptureResponse;
import uk.gov.pay.connector.service.epdq.EpdqPaymentProvider;
import uk.gov.pay.connector.service.epdq.EpdqSha512SignatureGenerator;
import uk.gov.pay.connector.util.TestClientFactory;

import javax.ws.rs.client.Client;
import java.io.IOException;
import java.net.URL;
import java.util.EnumMap;
import java.util.Map;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.is;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.model.domain.GatewayAccountEntity.Type.TEST;
import static uk.gov.pay.connector.util.AuthUtils.buildAuthCardDetails;
import static uk.gov.pay.connector.util.SystemUtils.envOrThrow;

//@Ignore("Ignoring as this test is failing in Jenkins because it's failing to locate the certificates - PP-1707")
@RunWith(MockitoJUnitRunner.class)
public class EpdqPaymentProviderTest {

    private String url = "https://mdepayments.epdq.co.uk/ncol/test";
    private String merchantId = envOrThrow("GDS_CONNECTOR_EPDQ_MERCHANT_ID");
    private String username = envOrThrow("GDS_CONNECTOR_EPDQ_USER");
    private String password = envOrThrow("GDS_CONNECTOR_EPDQ_PASSWORD");
    private String shaInPassphrase = envOrThrow("GDS_CONNECTOR_EPDQ_SHA_IN_PASSPHRASE");
    private ChargeEntity chargeEntity;
    private MetricRegistry mockMetricRegistry;
    private Histogram mockHistogram;
    private Counter mockCounter;

    @Before
    public void setUpAndCheckThatEpdqIsUp() {
        try {
            new URL(url).openConnection().connect();
            Map<String, String> validEpdqCredentials = ImmutableMap.of(
                    "merchant_id", merchantId,
                    "username", username,
                    "password", password,
                    "sha_in_passphrase", shaInPassphrase);
            GatewayAccountEntity validGatewayAccount = new GatewayAccountEntity();
            validGatewayAccount.setId(123L);
            validGatewayAccount.setGatewayName("epdq");
            validGatewayAccount.setCredentials(validEpdqCredentials);
            validGatewayAccount.setType(TEST);

            chargeEntity = aValidChargeEntity()
                    .withGatewayAccountEntity(validGatewayAccount)
                    .withTransactionId(randomUUID().toString())
                    .build();

            mockMetricRegistry = mock(MetricRegistry.class);
            mockHistogram = mock(Histogram.class);
            mockCounter = mock(Counter.class);
            when(mockMetricRegistry.histogram(anyString())).thenReturn(mockHistogram);
            when(mockMetricRegistry.counter(anyString())).thenReturn(mockCounter);
        } catch (IOException ex) {
            Assume.assumeTrue(false);
        }
    }

    @Test
    public void shouldAuthoriseSuccessfully() throws Exception {
        PaymentProvider paymentProvider = getEpdqPaymentProvider();
        AuthorisationGatewayRequest request = buildAuthorisationRequest(chargeEntity);
        GatewayResponse<EpdqAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());
    }

    @Test
    public void shouldCaptureSuccessfully() throws Exception {
        PaymentProvider paymentProvider = getEpdqPaymentProvider();
        AuthorisationGatewayRequest request = buildAuthorisationRequest(chargeEntity);
        GatewayResponse<EpdqAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());

        GatewayResponse<EpdqAuthorisationResponse> authorisationResponse = response;

        String transactionId = authorisationResponse.getBaseResponse().get().getTransactionId();
        assertThat(is(transactionId, notNull()));
        CaptureGatewayRequest captureRequest = buildCaptureRequest(chargeEntity, transactionId);
        GatewayResponse<EpdqCaptureResponse> captureResponse = paymentProvider.capture(captureRequest);
        assertTrue(captureResponse.isSuccessful());
    }

    @Test
    public void shouldCancelSuccessfully() throws Exception {
        PaymentProvider paymentProvider = getEpdqPaymentProvider();
        AuthorisationGatewayRequest request = buildAuthorisationRequest(chargeEntity);
        GatewayResponse<EpdqAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());

        GatewayResponse<EpdqAuthorisationResponse> authorisationResponse = response;

        String transactionId = authorisationResponse.getBaseResponse().get().getTransactionId();
        assertThat(is(transactionId, notNull()));
        CancelGatewayRequest cancelRequest = buildCancelRequest(chargeEntity, transactionId);
        GatewayResponse<EpdqCaptureResponse> cancelResponse = paymentProvider.cancel(cancelRequest);
        assertTrue(cancelResponse.isSuccessful());
    }

    @Test
    public void shouldCaptureAndRefund() throws Exception {
        PaymentProvider paymentProvider = getEpdqPaymentProvider();

        AuthorisationGatewayRequest request = buildAuthorisationRequest(chargeEntity);
        GatewayResponse<EpdqAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());

        GatewayResponse<EpdqAuthorisationResponse> authorisationResponse = response;

        String transactionId = authorisationResponse.getBaseResponse().get().getTransactionId();
        assertThat(is(transactionId, notNull()));
        CaptureGatewayRequest captureRequest = buildCaptureRequest(chargeEntity, transactionId);
        GatewayResponse<EpdqCaptureResponse> captureResponse = paymentProvider.capture(captureRequest);
        assertTrue(captureResponse.isSuccessful());

        RefundEntity refundEntity = new RefundEntity(chargeEntity, 1L);

        GatewayResponse refundGatewayResponse = paymentProvider.refund(RefundGatewayRequest.valueOf(refundEntity));
        assertTrue(refundGatewayResponse.isSuccessful());

    }


    private PaymentProvider getEpdqPaymentProvider() throws Exception {
        Client client = TestClientFactory.createJerseyClient();
        GatewayClient gatewayClient = new GatewayClient(client, ImmutableMap.of(TEST.toString(), url),
            EpdqPaymentProvider.includeSessionIdentifier(), mockMetricRegistry);
        EnumMap<GatewayOperation, GatewayClient> gatewayClients = GatewayOperationClientBuilder.builder()
                .authClient(gatewayClient)
                .captureClient(gatewayClient)
                .cancelClient(gatewayClient)
                .refundClient(gatewayClient)
                .build();
        return new EpdqPaymentProvider(gatewayClients, new EpdqSha512SignatureGenerator());
    }

    public static AuthorisationGatewayRequest buildAuthorisationRequest(ChargeEntity chargeEntity) {
        Address address = Address.anAddress();
        address.setLine1("41");
        address.setLine2("Scala Street");
        address.setCity("London");
        address.setCounty("London");
        address.setPostcode("EC2A 1AE");
        address.setCountry("GB");

        AuthCardDetails authCardDetails = aValidEpdqCard();
        authCardDetails.setAddress(address);

        return new AuthorisationGatewayRequest(chargeEntity, authCardDetails);
    }

    public static CaptureGatewayRequest buildCaptureRequest(ChargeEntity chargeEntity, String transactionId) {
        chargeEntity.setGatewayTransactionId(transactionId);
        return CaptureGatewayRequest.valueOf(chargeEntity);
    }

    public static AuthCardDetails aValidEpdqCard() {
        String validSandboxCard = "5555444433331111";
        return buildAuthCardDetails(validSandboxCard, "737", "08/18", "visa");
    }

    private CancelGatewayRequest buildCancelRequest(ChargeEntity chargeEntity, String transactionId) {
        chargeEntity.setGatewayTransactionId(transactionId);
        return CancelGatewayRequest.valueOf(chargeEntity);
    }


}

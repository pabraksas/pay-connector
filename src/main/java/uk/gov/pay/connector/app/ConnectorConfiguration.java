package uk.gov.pay.connector.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.db.DataSourceFactory;
import uk.gov.pay.connector.app.config.EmittedEventSweepConfig;
import uk.gov.pay.connector.app.config.EventEmitterConfig;
import uk.gov.pay.connector.app.config.ExpungeConfig;
import uk.gov.pay.connector.app.config.RestClientConfig;
import uk.gov.pay.connector.gateway.PaymentGatewayName;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class ConnectorConfiguration extends Configuration {

    @Valid
    @NotNull
    private WorldpayConfig worldpayConfig;

    @Valid
    @NotNull
    private ExecutorServiceConfig executorServiceConfig = new ExecutorServiceConfig();

    @Valid
    @NotNull
    private CaptureProcessConfig captureProcessConfig;

    @Valid
    @NotNull
    @JsonProperty("notifyConfig")
    private NotifyConfiguration notifyConfig;

    @Valid
    @NotNull
    private GatewayConfig smartpayConfig;

    @Valid
    @NotNull
    private GatewayConfig epdqConfig;

    @Valid
    @NotNull
    private StripeGatewayConfig stripeConfig;

    @Valid
    @NotNull
    private DataSourceFactory dataSourceFactory;

    @Valid
    @NotNull
    private JPAConfiguration jpaConfiguration;

    @Valid
    @NotNull
    private LinksConfig links = new LinksConfig();

    @Valid
    @NotNull
    @JsonProperty("jerseyClient")
    private JerseyClientConfiguration jerseyClientConfig;

    @Valid
    @NotNull
    @JsonProperty("customJerseyClient")
    private CustomJerseyClientConfiguration customJerseyClient;

    @Valid
    @NotNull
    @JsonProperty("chargesSweepConfig")
    private ChargeSweepConfig chargeSweepConfig;

    @NotNull
    private EmittedEventSweepConfig emittedEventSweepConfig;

    @NotNull
    private EventEmitterConfig eventEmitterConfig;

    @Valid
    @NotNull
    @JsonProperty("expungeConfig")
    private ExpungeConfig expungeConfig;

    @NotNull
    private String graphiteHost;

    @NotNull
    private String graphitePort;

    @NotNull
    private Boolean xrayEnabled;

    @NotNull
    private Boolean emitPaymentStateTransitionEvents;

    @Valid
    @NotNull
    @JsonProperty("sqsConfig")
    private SqsConfig sqsConfig;

    @Valid
    @NotNull
    @JsonProperty("eventQueue")
    private EventQueueConfig eventQueueConfig;

    @Valid
    @NotNull
    private RestClientConfig restClientConfig;

    @NotNull
    @JsonProperty("ledgerBaseURL")
    private String ledgerBaseUrl;

    public String getLedgerBaseUrl() {
        return ledgerBaseUrl;
    }

    @JsonProperty("database")
    public DataSourceFactory getDataSourceFactory() {
        return dataSourceFactory;
    }

    public LinksConfig getLinks() {
        return links;
    }

    @JsonProperty("worldpay")
    public WorldpayConfig getWorldpayConfig() {
        return worldpayConfig;
    }

    @JsonProperty("smartpay")
    public GatewayConfig getSmartpayConfig() {
        return smartpayConfig;
    }

    @JsonProperty("epdq")
    public GatewayConfig getEpdqConfig() {
        return epdqConfig;
    }

    @JsonProperty("stripe")
    public StripeGatewayConfig getStripeConfig() {
        return stripeConfig;
    }

    public GatewayConfig getGatewayConfigFor(PaymentGatewayName gateway) {
        switch (gateway) {
            case WORLDPAY:
                return getWorldpayConfig();
            case SMARTPAY:
                return getSmartpayConfig();
            case EPDQ:
                return getEpdqConfig();
            default:
                throw new PaymentGatewayName.Unsupported();
        }
    }

    @JsonProperty("jpa")
    public JPAConfiguration getJpaConfiguration() {
        return jpaConfiguration;
    }

    public ExecutorServiceConfig getExecutorServiceConfig() {
        return executorServiceConfig;
    }

    public NotifyConfiguration getNotifyConfiguration() {
        return notifyConfig;
    }

    public JerseyClientConfiguration getClientConfiguration() {
        return jerseyClientConfig;
    }

    public CustomJerseyClientConfiguration getCustomJerseyClient() {
        return customJerseyClient;
    }

    public String getGraphiteHost() {
        return graphiteHost;
    }

    public String getGraphitePort() {
        return graphitePort;
    }

    public CaptureProcessConfig getCaptureProcessConfig() {
        return captureProcessConfig;
    }

    public Boolean isXrayEnabled() {
        return xrayEnabled;
    }

    public ChargeSweepConfig getChargeSweepConfig() {
        return chargeSweepConfig;
    }

    public SqsConfig getSqsConfig() {
        return sqsConfig;
    }

    public EventQueueConfig getEventQueueConfig() {
        return eventQueueConfig;
    }

    public Boolean getEmitPaymentStateTransitionEvents() {
        return emitPaymentStateTransitionEvents;
    }

    public RestClientConfig getRestClientConfig() {
        return restClientConfig;
    }

    public EmittedEventSweepConfig getEmittedEventSweepConfig() {
        return emittedEventSweepConfig;
    }

    public EventEmitterConfig getEventEmitterConfig() {
        return eventEmitterConfig;
    }

    public ExpungeConfig getExpungeConfig() {
        return expungeConfig;
    }
}

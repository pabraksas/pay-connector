package uk.gov.pay.connector.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.model.StatusUpdates;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.GatewayAccount;
import uk.gov.pay.connector.model.domain.GatewayAccountEntity;
import uk.gov.pay.connector.service.ChargeStatusBlacklist;
import uk.gov.pay.connector.service.PaymentProvider;
import uk.gov.pay.connector.service.PaymentProviders;
import uk.gov.pay.connector.util.NotificationUtil;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static javax.ws.rs.core.Response.Status.BAD_GATEWAY;

@Path("/")
public class NotificationResource {

    private static final Logger logger = LoggerFactory.getLogger(NotificationResource.class);
    private PaymentProviders providers;
    private ChargeDao chargeDao;
    private NotificationUtil notificationUtil = new NotificationUtil(new ChargeStatusBlacklist());

    @Inject
    public NotificationResource(PaymentProviders providers, ChargeDao chargeDao) {
        this.providers = providers;
        this.chargeDao = chargeDao;
    }

    @POST
    @PermitAll
    @Path("v1/api/notifications/smartpay")
    public Response authoriseSmartpayNotifications(String notification) throws IOException {
        return handleNotification("smartpay", notification);
    }

    @POST
    @Path("v1/api/notifications/{provider}")
    public Response handleNotification(@PathParam("provider") String provider, String notification) {

        logger.info("Received notification from " + provider + ": " + notification);

        PaymentProvider paymentProvider = providers.resolve(provider);
        StatusUpdates statusUpdates = paymentProvider.handleNotification(notification, notificationUtil::payloadChecks, findAccountByTransactionId(provider), accountUpdater(provider));

        if (!statusUpdates.successful()) {
            return Response.status(BAD_GATEWAY).build();
        }

        return Response.ok(statusUpdates.getResponseForProvider()).build();
    }

    private Consumer<StatusUpdates> accountUpdater(String provider) {
        return statusUpdates ->
                statusUpdates.getStatusUpdates().forEach(update -> updateCharge(chargeDao, provider, update.getKey(), update.getValue()));
    }

    private Function<String, Optional<GatewayAccountEntity>> findAccountByTransactionId(String provider) {
        return transactionId ->
                chargeDao.findByProviderAndTransactionId(provider, transactionId)
                        .map((chargeEntity) ->
                                Optional.of(chargeEntity.getGatewayAccount()))
                        .orElseGet(() -> {
                            logger.error("Could not find account for transaction id " + transactionId);
                            return Optional.empty();
                        });
    }

    private static void updateCharge(ChargeDao chargeDao, String provider, String transactionId, ChargeStatus value) {
        Optional<ChargeEntity> charge = chargeDao.findByProviderAndTransactionId(provider, transactionId);
        if (charge.isPresent()) {
            ChargeEntity entity = charge.get();
            entity.setStatus(value);
            chargeDao.mergeAndNotifyStatusHasChanged(entity);
        } else {
            logger.error("Error when trying to update transaction id " + transactionId + " to status " + value);
        }
    }
}
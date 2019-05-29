package uk.gov.pay.connector.paymentprocessor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.charge.dao.ChargeDao;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.gateway.CaptureResponse;
import uk.gov.pay.connector.queue.CaptureQueue;
import uk.gov.pay.connector.queue.ChargeCaptureMessage;
import uk.gov.pay.connector.queue.QueueException;

import javax.inject.Inject;
import java.util.List;

// @TODO(sfount) replace `CardCaptureProcess` when feature flag is switched
public class CardCaptureMessageProcess {

    private static final Logger LOGGER = LoggerFactory.getLogger(CardCaptureMessageProcess.class);
    private final CaptureQueue captureQueue;
    private final Boolean captureUsingSqs;
    private final ChargeDao chargeDao;
    private final int maximumCaptureRetries;
    private CardCaptureService cardCaptureService;

    @Inject
    public CardCaptureMessageProcess(CaptureQueue captureQueue, CardCaptureService cardCaptureService,
                                     ConnectorConfiguration connectorConfiguration, ChargeDao chargeDao) {
        this.captureQueue = captureQueue;
        this.cardCaptureService = cardCaptureService;
        this.captureUsingSqs = connectorConfiguration.getCaptureProcessConfig().getCaptureUsingSQS();
        this.chargeDao = chargeDao;
        this.maximumCaptureRetries = connectorConfiguration.getCaptureProcessConfig().getMaximumRetries();
    }

    public void handleCaptureMessages() throws QueueException {
        List<ChargeCaptureMessage> captureMessages = captureQueue.retrieveChargesForCapture();
        for (ChargeCaptureMessage message: captureMessages) {
            try {
                LOGGER.info("Charge capture message received - {}", message.getChargeId());

                if (captureUsingSqs) {
                    runCapture(message);
                } else {
                    LOGGER.info("Charge capture not enabled for message capture request - {}", message.getChargeId());
                }
            } catch (Exception e) {
                LOGGER.warn("Error capturing charge from SQS message [{}]", e.getMessage());
            }
        }
    }

    private void runCapture(ChargeCaptureMessage captureMessage) throws QueueException {
        String externalChargeId = captureMessage.getChargeId();

        CaptureResponse gatewayResponse = cardCaptureService.doCapture(externalChargeId);

        // @TODO(sfount) handling gateway response failure should be considered in PP-5171
        if (gatewayResponse.isSuccessful()) {
            captureQueue.markMessageAsProcessed(captureMessage);
        } else {
            LOGGER.info(
                    "Failed to capture [externalChargeId={}] due to: {}",
                    externalChargeId,
                    gatewayResponse.getErrorMessage()
            );
            handleCaptureRetry(captureMessage);
        }
    }

    private void handleCaptureRetry(ChargeCaptureMessage captureMessage) throws QueueException {
        int numberOfChargeRetries = chargeDao.countCaptureRetriesForChargeExternalId(captureMessage.getChargeId());
        boolean shouldRetry = numberOfChargeRetries <= maximumCaptureRetries;

        if (shouldRetry) {
            captureQueue.scheduleMessageForRetry(captureMessage);
        } else {
            cardCaptureService.markChargeAsCaptureError(captureMessage.getChargeId());
            captureQueue.markMessageAsProcessed(captureMessage);
        }
    }
}

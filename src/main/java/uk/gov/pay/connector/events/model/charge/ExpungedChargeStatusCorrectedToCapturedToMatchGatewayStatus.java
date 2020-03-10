package uk.gov.pay.connector.events.model.charge;

import java.time.ZonedDateTime;

public class ExpungedChargeStatusCorrectedToCapturedToMatchGatewayStatus extends PaymentEventWithoutDetails {
    public ExpungedChargeStatusCorrectedToCapturedToMatchGatewayStatus(String resourceExternalId, ZonedDateTime timestamp) {
        super(resourceExternalId, timestamp);
    }
}

package uk.gov.pay.connector.service.worldpay;

import uk.gov.pay.connector.service.BaseStatusMapper;
import uk.gov.pay.connector.service.StatusMapper;

import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.model.domain.RefundStatus.REFUNDED;
import static uk.gov.pay.connector.model.domain.RefundStatus.REFUND_ERROR;

public class WorldpayStatusMapper {

    private static final BaseStatusMapper<String> STATUS_MAPPER =
            BaseStatusMapper
                    .<String>builder()
                    .ignore("AUTHORISED")
                    .ignore("CANCELLED")
                    .map("CAPTURED", CAPTURED)
                    .ignore("REFUSED")
                    .ignore("REFUSED_BY_BANK")
                    .ignore("SENT_FOR_AUTHORISATION")
                    .map("REFUNDED", REFUNDED)
                    .map("REFUND_FAILED", REFUND_ERROR)
                    .build();

    public static StatusMapper<String> get() {
        return STATUS_MAPPER;
    }
}

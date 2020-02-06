package uk.gov.pay.connector.gateway.model.request;

import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.gateway.GatewayOperation;

public class RefundGatewayRequest implements GatewayRequest {

    private final GatewayAccountEntity gatewayAccountEntity;
    private final String amount;
    private final String transactionId;
    private final String refundExternalId;
    private final String chargeExternalId;

    private RefundGatewayRequest(String transactionId, GatewayAccountEntity gatewayAccount, String amount, String refundExternalId, String chargeExternalId) {
        this.transactionId = transactionId;
        this.gatewayAccountEntity = gatewayAccount;
        this.amount = amount;
        this.refundExternalId = refundExternalId;
        this.chargeExternalId = chargeExternalId;
    }

    public String getAmount() {
        return amount;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getChargeExternalId() {
        return chargeExternalId;
    }

    @Override
    public GatewayAccountEntity getGatewayAccount() {
        return gatewayAccountEntity;
    }

    @Override
    public GatewayOperation getRequestType() {
        return GatewayOperation.REFUND;
    }

    public String getRefundExternalId() {
        return refundExternalId;
    }
    
    /**
     * <p>
     * For Worldpay ->
     *          transactionId = The original charges.gateway_transaction_id generated by us <br>
     *          reference = our externalId for this refund, <b> this is our link to the worldpay refund transaction</b>
     * </p>
     *
     * <p>
     * For Smartpay ->
     *         transactionId = The original charges.gateway_transaction_id (a.k.a pspReference) given to us by smartpay at the payment AUTHORIZATION response. <br>
     *         reference = our externalId for this refund, <b> this is NOT our link to smartpay refund. its only a pass through value as far as smartpay is concerned </b> <br>
     *
     *        Smartpay does not respond with our reference when SUBMIT REFUND, however when they notify us REFUNDED they return
     *          -  Our `reference` we sent above will also be returned as `merchantReference`.
     *          - `transactionId` above will also be returned as `originalReference`.
     *          - Their reference to the Refund in smartpay will be returned as `pspReference`.
     * </p>
     */
    public static RefundGatewayRequest valueOf(Charge charge, RefundEntity refundEntity, GatewayAccountEntity gatewayAccountEntity) {
        return new RefundGatewayRequest(
                charge.getGatewayTransactionId(),
                gatewayAccountEntity,
                String.valueOf(refundEntity.getAmount()),
                refundEntity.getExternalId(),
                charge.getExternalId()
        );
    }
    
    @Override
    public String toString() {
        return new StringBuilder()
                .append("RefundGatewayRequest[\n")
                .append("transactionId: ")
                .append(transactionId)
                .append("\nrefundExternalId: ")
                .append(refundExternalId)
                .append("]")
                .toString();
    }
}

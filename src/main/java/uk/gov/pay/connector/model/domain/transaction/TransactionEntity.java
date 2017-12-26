package uk.gov.pay.connector.model.domain.transaction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import uk.gov.pay.connector.model.domain.AbstractVersionedEntity;
import uk.gov.pay.connector.model.domain.PaymentRequestEntity;
import uk.gov.pay.connector.model.domain.Status;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "transactions")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "operation")
@SequenceGenerator(name = "transactions_id_seq",
        sequenceName = "transactions_id_seq",
        allocationSize = 1)
public abstract class TransactionEntity<S extends Status, T extends TransactionEventEntity<S, T>> extends AbstractVersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transactions_id_seq")
    @JsonIgnore
    private Long id;
    @Column(name = "amount")
    private Long amount;
    @Column(name = "operation")
    @Enumerated(EnumType.STRING)
    private TransactionOperation operation;
    @ManyToOne
    @JoinColumn(name = "payment_request_id", referencedColumnName = "id", updatable = false)
    private PaymentRequestEntity paymentRequest;
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL)
    private List<T> transactionEvents = new ArrayList<>();

    TransactionEntity(TransactionOperation operation) {
        this.operation = operation;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public PaymentRequestEntity getPaymentRequest() {
        return paymentRequest;
    }

    public void setPaymentRequest(PaymentRequestEntity paymentRequest) {
        this.paymentRequest = paymentRequest;
    }

    public TransactionOperation getOperation() {
        return operation;
    }

    public abstract S getStatus();
    abstract void setStatus(S status);

    public List<T> getTransactionEvents() {
        return transactionEvents;
    }

    public final void updateStatus(S newStatus) {
        updateStatus(newStatus, createNewTransactionEvent());
    }

    void updateStatus(S newStatus, T transactionEvent) {
        setStatus(newStatus);
        addTransactionEvent(transactionEvent, newStatus);
    }

    private void addTransactionEvent(T transactionEvent, S newStatus) {
        transactionEvent.setTransaction(this);
        transactionEvent.setStatus(newStatus);
        transactionEvent.setUpdated(ZonedDateTime.now());
        getTransactionEvents().add(transactionEvent);
    }

    protected abstract T createNewTransactionEvent();
}
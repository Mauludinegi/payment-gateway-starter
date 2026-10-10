package io.github.mauludinegi.payments.payment;

import io.github.mauludinegi.payments.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One try at paying an order through one channel. Changing the payment method creates a new attempt. */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    private Instruction.Type instructionType;

    @Column(length = 2048)
    private String instructionValue;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private String idempotencyKey;

    private String mobileNumber;

    /** Why the gateway's answer is still unknown, while the attempt waits for reconciliation. */
    private String gatewayError;

    @Version
    private Long version;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(Order order, Provider provider, Channel channel, String mobileNumber, String idempotencyKey,
                          Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.provider = provider;
        this.channel = channel;
        this.mobileNumber = mobileNumber;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public void attachGatewayPayment(String providerRef, Instruction instruction, Instant expiresAt, Instant now) {
        this.providerRef = providerRef;
        this.instructionType = instruction.type();
        this.instructionValue = instruction.value();
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
        this.gatewayError = null;
        this.updatedAt = now;
    }

    /** A webhook arrived before our create call returned; its gateway id is enough for status checks. */
    public void adoptProviderRef(String providerRef, Instant now) {
        if (this.providerRef == null) {
            this.providerRef = providerRef;
            this.updatedAt = now;
        }
    }

    /** The create call timed out or hit a gateway error, so the payment may or may not exist there. */
    public void recordUnconfirmed(String error, Instant now) {
        this.gatewayError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        this.updatedAt = now;
    }

    /** Pending, but the gateway has not confirmed creating it yet; no instruction to show. */
    public boolean isUnconfirmed() {
        return status == PaymentStatus.PENDING && providerRef == null;
    }

    public void changeStatus(PaymentStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public Instruction instruction() {
        return instructionType == null ? null : new Instruction(instructionType, instructionValue);
    }

    public UUID getId() { return id; }
    public Order getOrder() { return order; }
    public Provider getProvider() { return provider; }
    public Channel getChannel() { return channel; }
    public String getProviderRef() { return providerRef; }
    public PaymentStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getMobileNumber() { return mobileNumber; }
    public String getGatewayError() { return gatewayError; }
}

package io.github.mauludinegi.payments.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false)
    private String description;

    private long amount;

    @Column(nullable = false)
    private String customerName;

    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant paidAt;

    @Version
    private Long version;

    protected Order() {
    }

    public Order(String reference, String description, long amount, String customerName, String customerEmail,
                 Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.description = description;
        this.amount = amount;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    /** Returns true only on the first transition to PAID. */
    public boolean markPaid(Instant at) {
        if (status == OrderStatus.PAID) {
            return false;
        }
        status = OrderStatus.PAID;
        paidAt = at;
        return true;
    }

    public void markExpired() {
        if (status == OrderStatus.PENDING_PAYMENT) {
            status = OrderStatus.EXPIRED;
        }
    }

    public boolean isPayable() {
        return status == OrderStatus.PENDING_PAYMENT;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public long getAmount() { return amount; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
}

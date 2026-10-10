package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.PaymentStatusService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per processed gateway event; the unique key makes redelivered webhooks a no-op. */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private String eventKey;

    @Column(nullable = false)
    private Instant receivedAt;

    private UUID attemptId;

    /** The status the gateway reported when re-checked, not the one in the payload. */
    @Enumerated(EnumType.STRING)
    private PaymentStatus confirmedStatus;

    @Enumerated(EnumType.STRING)
    private PaymentStatusService.Outcome outcome;

    private String providerRef;

    /** Our attempt id as the gateway echoed it, before it was matched to a payment. */
    private UUID attemptHint;

    /** Matched no payment when it arrived; replayed until it does. */
    private boolean replayPending;

    protected WebhookEvent() {
    }

    public WebhookEvent(Provider provider, String eventKey, Instant receivedAt, UUID attemptId, PaymentStatus confirmedStatus) {
        this.provider = provider;
        this.eventKey = eventKey;
        this.receivedAt = receivedAt;
        this.attemptId = attemptId;
        this.confirmedStatus = confirmedStatus;
    }

    /** A verified event for a payment we cannot find yet, e.g. one whose create call has not returned. */
    public static WebhookEvent queued(Provider provider, String eventKey, Instant receivedAt, String providerRef, UUID attemptHint) {
        WebhookEvent event = new WebhookEvent(provider, eventKey, receivedAt, null, null);
        event.providerRef = providerRef;
        event.attemptHint = attemptHint;
        event.replayPending = true;
        return event;
    }

    public void recordOutcome(PaymentStatusService.Outcome outcome) {
        this.outcome = outcome;
    }

    public void matched(UUID attemptId, PaymentStatus confirmedStatus) {
        this.attemptId = attemptId;
        this.confirmedStatus = confirmedStatus;
        this.replayPending = false;
    }

    /** Gives up on an event that never matched a payment of ours. */
    public void abandon() {
        this.replayPending = false;
    }

    public Long getId() { return id; }
    public Provider getProvider() { return provider; }
    public String getEventKey() { return eventKey; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getAttemptId() { return attemptId; }
    public PaymentStatus getConfirmedStatus() { return confirmedStatus; }
    public PaymentStatusService.Outcome getOutcome() { return outcome; }
    public String getProviderRef() { return providerRef; }
    public UUID getAttemptHint() { return attemptHint; }
    public boolean isReplayPending() { return replayPending; }
}

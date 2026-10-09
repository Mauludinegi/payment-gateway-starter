package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.payment.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

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

    protected WebhookEvent() {
    }

    public WebhookEvent(Provider provider, String eventKey, Instant receivedAt) {
        this.provider = provider;
        this.eventKey = eventKey;
        this.receivedAt = receivedAt;
    }
}

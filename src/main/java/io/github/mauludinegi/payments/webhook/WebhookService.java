package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.gateway.WebhookNotification;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.PaymentStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook handling in four steps: verify the sender, skip events already processed, re-check the
 * status with the gateway (the payload alone is never trusted), then apply it in one transaction.
 * A verified event that matches no payment yet is stored and replayed, never dropped.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** How long an unmatched event keeps being replayed before it is given up on. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(3);

    public enum Result { PROCESSED, DUPLICATE, QUEUED }

    private final GatewayRegistry gateways;
    private final PaymentAttemptRepository attempts;
    private final WebhookEventRepository events;
    private final PaymentStatusService statuses;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookService(GatewayRegistry gateways, PaymentAttemptRepository attempts, WebhookEventRepository events,
                          PaymentStatusService statuses, TransactionTemplate tx, Clock clock) {
        this.gateways = gateways;
        this.attempts = attempts;
        this.events = events;
        this.statuses = statuses;
        this.tx = tx;
        this.clock = clock;
    }

    public Result handle(Provider provider, Map<String, String> headers, String body) {
        PaymentGateway gateway = gateways.get(provider);
        WebhookNotification notification = gateway.parseWebhook(headers, body);

        if (events.existsByProviderAndEventKey(provider, notification.eventKey())) {
            return Result.DUPLICATE;
        }
        Optional<PaymentAttempt> found = match(provider, notification.attemptId(), notification.providerRef());
        if (found.isEmpty()) {
            return queue(provider, notification);
        }
        return process(gateway, provider, notification.eventKey(), found.get(), notification.providerRef(), null);
    }

    /** Applies stored events whose payment can now be found. Returns how many were applied. */
    public int replayQueued() {
        int applied = 0;
        for (WebhookEvent queued : events.findTop100ByReplayPendingTrueOrderByReceivedAt()) {
            try {
                if (replay(queued)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                log.warn("Replaying {} webhook {} failed, retrying later: {}", queued.getProvider(), queued.getEventKey(), e.getMessage());
            }
        }
        return applied;
    }

    private boolean replay(WebhookEvent queued) {
        Provider provider = queued.getProvider();
        Optional<PaymentAttempt> found = match(provider, queued.getAttemptHint(), queued.getProviderRef());
        if (found.isEmpty()) {
            if (queued.getReceivedAt().isBefore(clock.instant().minus(REPLAY_WINDOW))) {
                tx.executeWithoutResult(s -> events.findById(queued.getId()).ifPresent(WebhookEvent::abandon));
                log.warn("Gave up on {} webhook {}: no payment {} after {}", provider, queued.getEventKey(), queued.getProviderRef(), REPLAY_WINDOW);
            }
            return false;
        }
        return process(gateways.get(provider), provider, queued.getEventKey(), found.get(), queued.getProviderRef(), queued.getId())
                == Result.PROCESSED;
    }

    private Optional<PaymentAttempt> match(Provider provider, UUID attemptId, String providerRef) {
        return Optional.ofNullable(attemptId)
                .flatMap(attempts::findWithOrder)
                .or(() -> providerRef == null ? Optional.empty() : attempts.findWithOrderByProviderRef(provider, providerRef))
                .filter(a -> a.getProvider() == provider);
    }

    private Result process(PaymentGateway gateway, Provider provider, String eventKey, PaymentAttempt attempt,
                           String payloadRef, Long queuedEventId) {
        String providerRef = attempt.getProviderRef() != null ? attempt.getProviderRef() : payloadRef;
        PaymentStatus confirmed = gateway.fetchStatus(providerRef);
        try {
            return tx.execute(status -> {
                WebhookEvent event;
                if (queuedEventId == null) {
                    event = events.saveAndFlush(new WebhookEvent(provider, eventKey, clock.instant(), attempt.getId(), confirmed));
                } else {
                    event = events.findById(queuedEventId).orElseThrow();
                    if (!event.isReplayPending()) {
                        return Result.DUPLICATE;
                    }
                    event.matched(attempt.getId(), confirmed);
                }
                // The webhook can beat our own create call; keep the gateway's id so later checks can use it.
                attempts.findById(attempt.getId())
                        .filter(a -> a.getProviderRef() == null && providerRef != null && confirmed.isFinal())
                        .ifPresent(a -> a.adoptProviderRef(providerRef, clock.instant()));
                PaymentStatusService.Outcome outcome = statuses.apply(attempt.getId(), confirmed);
                event.recordOutcome(outcome);
                log.info("{} webhook {}: payment {} -> {} ({})", provider, eventKey, attempt.getId(), confirmed, outcome);
                return Result.PROCESSED;
            });
        } catch (DataIntegrityViolationException e) {
            if (events.existsByProviderAndEventKey(provider, eventKey)) {
                return Result.DUPLICATE;
            }
            throw e;
        }
    }

    private Result queue(Provider provider, WebhookNotification notification) {
        try {
            tx.executeWithoutResult(status -> events.saveAndFlush(WebhookEvent.queued(provider, notification.eventKey(),
                    clock.instant(), notification.providerRef(), notification.attemptId())));
        } catch (DataIntegrityViolationException e) {
            if (events.existsByProviderAndEventKey(provider, notification.eventKey())) {
                return Result.DUPLICATE;
            }
            throw e;
        }
        log.warn("{} webhook {} matches no payment yet ({}); stored for replay", provider, notification.eventKey(), notification.providerRef());
        return Result.QUEUED;
    }
}

package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Settles what the request path could not: payments whose create call timed out (retried where the
 * gateway allows it, see {@link io.github.mauludinegi.payments.gateway.PaymentGateway#canRetryCreate}; the
 * rest are settled by their webhook or expire) and webhooks that arrived before their payment could be matched.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    /** Leaves a create that is still in flight on the request thread alone. */
    static final Duration SETTLE = Duration.ofSeconds(30);

    private final PaymentAttemptRepository attempts;
    private final CheckoutService checkout;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final WebhookService webhooks;
    private final Clock clock;

    public PaymentReconciler(PaymentAttemptRepository attempts, CheckoutService checkout, GatewayRegistry gateways,
                             PaymentStatusService statuses, WebhookService webhooks, Clock clock) {
        this.attempts = attempts;
        this.checkout = checkout;
        this.gateways = gateways;
        this.statuses = statuses;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    public void run() {
        run(clock.instant().minus(SETTLE));
    }

    /** Settles payments whose create call was last tried before {@code settledBefore}, then replays queued webhooks. */
    public void run(Instant settledBefore) {
        for (UUID id : attempts.findUnconfirmedIds(settledBefore)) {
            try {
                reconcile(id);
            } catch (RuntimeException e) {
                log.warn("Could not reconcile payment {}: {}", id, e.getMessage());
            }
        }
        int replayed = webhooks.replayQueued();
        if (replayed > 0) {
            log.info("Replayed {} queued webhook(s)", replayed);
        }
    }

    private void reconcile(UUID attemptId) {
        checkout.confirmAtGateway(attemptId, true);
        PaymentAttempt attempt = attempts.findWithOrder(attemptId).orElseThrow();
        if (attempt.getStatus() != PaymentStatus.PENDING || attempt.getProviderRef() == null || attempt.getOrder().isPayable()) {
            return;
        }
        // Recovered after the order was settled another way: close it so it cannot be paid as well.
        PaymentGateway gateway = gateways.get(attempt.getProvider());
        try {
            gateway.cancel(attempt.getProviderRef());
        } catch (RuntimeException e) {
            log.warn("Could not cancel recovered payment {}: {}", attemptId, e.getMessage());
            return;
        }
        statuses.apply(attemptId, PaymentStatus.CANCELLED);
    }
}

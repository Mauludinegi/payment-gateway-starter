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
import java.util.Map;
import java.util.Optional;

/**
 * Webhook handling in four steps: verify the sender, skip events already processed, re-check the
 * status with the gateway (the payload alone is never trusted), then apply it in one transaction.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Result { PROCESSED, DUPLICATE, UNKNOWN_PAYMENT }

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
        Optional<PaymentAttempt> found = Optional.ofNullable(notification.attemptId())
                .flatMap(attempts::findWithOrder)
                .or(() -> attempts.findWithOrderByProviderRef(provider, notification.providerRef()))
                .filter(a -> a.getProvider() == provider);
        if (found.isEmpty()) {
            log.warn("{} webhook for unknown payment {}", provider, notification.providerRef());
            return Result.UNKNOWN_PAYMENT;
        }
        PaymentAttempt attempt = found.get();
        String providerRef = attempt.getProviderRef() != null ? attempt.getProviderRef() : notification.providerRef();
        PaymentStatus confirmed = gateway.fetchStatus(providerRef);

        try {
            return tx.execute(status -> {
                WebhookEvent event = events.saveAndFlush(
                        new WebhookEvent(provider, notification.eventKey(), clock.instant(), attempt.getId(), confirmed));
                PaymentStatusService.Outcome outcome = statuses.apply(attempt.getId(), confirmed);
                event.recordOutcome(outcome);
                log.info("{} webhook {}: payment {} -> {} ({})", provider, notification.eventKey(), attempt.getId(), confirmed, outcome);
                return Result.PROCESSED;
            });
        } catch (DataIntegrityViolationException e) {
            return Result.DUPLICATE;
        }
    }
}

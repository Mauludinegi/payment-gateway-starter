package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The only place payment and order statuses change. Rules:
 * <ul>
 *   <li>a succeeded payment is never downgraded, whatever arrives later;</li>
 *   <li>a late success still counts (the customer paid an old VA after it was cancelled or expired);</li>
 *   <li>the order becomes PAID once, and {@link OrderPaidEvent} fires once.</li>
 * </ul>
 */
@Service
public class PaymentStatusService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusService.class);

    public enum Outcome { UPDATED, UNCHANGED, IGNORED }

    private final PaymentAttemptRepository attempts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentStatusService(PaymentAttemptRepository attempts, ApplicationEventPublisher events, Clock clock) {
        this.attempts = attempts;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Outcome apply(UUID attemptId, PaymentStatus reported) {
        PaymentAttempt attempt = attempts.findWithOrder(attemptId)
                .orElseThrow(() -> new NotFoundException("Payment " + attemptId + " not found"));
        PaymentStatus current = attempt.getStatus();
        Instant now = clock.instant();

        if (current == reported) {
            return Outcome.UNCHANGED;
        }
        if (current == PaymentStatus.SUCCEEDED) {
            log.warn("Ignoring {} for payment {}: it already succeeded", reported, attemptId);
            return Outcome.IGNORED;
        }
        if (current.isFinal() && reported != PaymentStatus.SUCCEEDED) {
            return Outcome.IGNORED;
        }

        attempt.changeStatus(reported, now);
        if (reported == PaymentStatus.SUCCEEDED) {
            Order order = attempt.getOrder();
            if (order.markPaid(now)) {
                events.publishEvent(new OrderPaidEvent(order.getId(), order.getReference(), order.getAmount(), attempt.getId(), attempt.getChannel()));
            } else {
                log.warn("Order {} was already paid; payment {} is a duplicate and needs a refund", order.getReference(), attemptId);
            }
        }
        return Outcome.UPDATED;
    }
}

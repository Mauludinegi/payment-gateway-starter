package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Not every gateway sends an expiry webhook (Xendit v3 does not), so pending payments past their
 * expiry are re-checked with the gateway and closed here. Orders expire once no payment is pending.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    private static final Duration GRACE = Duration.ofMinutes(2);

    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final Clock clock;

    public ExpiryJob(PaymentAttemptRepository attempts, OrderRepository orders, GatewayRegistry gateways,
                     PaymentStatusService statuses, Clock clock) {
        this.attempts = attempts;
        this.orders = orders;
        this.gateways = gateways;
        this.statuses = statuses;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payments.expiry-check-interval:PT1M}")
    public void run() {
        Instant now = clock.instant();
        for (PaymentAttempt attempt : attempts.findByStatusAndExpiresAtBefore(PaymentStatus.PENDING, now.minus(GRACE))) {
            closeAttempt(attempt);
        }
        expireOrders(now);
    }

    private void closeAttempt(PaymentAttempt attempt) {
        PaymentStatus status = PaymentStatus.EXPIRED;
        if (attempt.getProviderRef() != null) {
            try {
                PaymentStatus remote = gateways.get(attempt.getProvider()).fetchStatus(attempt.getProviderRef());
                if (remote != PaymentStatus.PENDING) {
                    status = remote;
                }
            } catch (RuntimeException e) {
                log.warn("Could not re-check payment {}: {}", attempt.getId(), e.getMessage());
                return;
            }
        }
        statuses.apply(attempt.getId(), status);
    }

    private void expireOrders(Instant now) {
        for (Order order : orders.findExpiredWithoutPendingPayment(now)) {
            try {
                statuses.expireOrder(order.getId());
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Order {} changed while expiring (probably paid); checking again next run", order.getReference());
            }
        }
    }
}

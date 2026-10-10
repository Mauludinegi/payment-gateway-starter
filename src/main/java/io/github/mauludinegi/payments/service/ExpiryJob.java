package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Not every gateway sends an expiry webhook (Xendit v3 does not), so pending payments past their
 * expiry are closed at the gateway and then here. Orders expire (and release stock) once no payment
 * is pending. Each run first lets {@link PaymentReconciler} settle unconfirmed payments and webhooks.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Duration UNCONFIRMED_GRACE = Duration.ofMinutes(15);

    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final PaymentReconciler reconciler;
    private final Clock clock;

    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastRun = Instant.EPOCH;

    public ExpiryJob(PaymentAttemptRepository attempts, OrderRepository orders, GatewayRegistry gateways,
                     PaymentStatusService statuses, PaymentReconciler reconciler, Clock clock,
                     @Value("${payments.expiry-check-interval:PT1M}") Duration interval) {
        this.attempts = attempts;
        this.orders = orders;
        this.gateways = gateways;
        this.statuses = statuses;
        this.reconciler = reconciler;
        this.clock = clock;
        this.interval = interval;
    }

    @Scheduled(fixedDelayString = "${payments.expiry-check-interval:PT1M}")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            lastRun = now;
            reconciler.run();
            for (PaymentAttempt attempt : attempts.findByStatusAndExpiresAtBefore(PaymentStatus.PENDING, now.minus(GRACE))) {
                closeAttempt(attempt);
            }
            expireOrders(now);
        } finally {
            running.set(false);
        }
    }

    /**
     * Starts a run in the background when the last one is older than the interval. Hosts that pause the app
     * between requests (Vercel) never fire the schedule, so incoming requests start the job instead.
     */
    public void runIfDue() {
        if (running.get() || clock.instant().isBefore(lastRun.plus(interval))) {
            return;
        }
        Thread.ofVirtual().name("expiry-job").start(this::run);
    }

    /**
     * Closes the payment at the gateway before closing it here, so stock is only released once the
     * customer can no longer pay. A payment the gateway still reports open is retried next run.
     */
    private void closeAttempt(PaymentAttempt attempt) {
        if (attempt.getProviderRef() == null) {
            // Never confirmed; the reconciler keeps retrying it. The gateway was given the same expiry,
            // so well past it the payment can no longer be made there either.
            if (attempt.getExpiresAt().isBefore(clock.instant().minus(UNCONFIRMED_GRACE))) {
                statuses.apply(attempt.getId(), PaymentStatus.EXPIRED);
            }
            return;
        }
        PaymentGateway gateway = gateways.get(attempt.getProvider());
        PaymentStatus remote;
        try {
            remote = gateway.fetchStatus(attempt.getProviderRef());
            if (remote == PaymentStatus.PENDING) {
                try {
                    gateway.cancel(attempt.getProviderRef());
                } catch (RuntimeException e) {
                    log.warn("Could not cancel expired payment {} at {}: {}", attempt.getId(), attempt.getProvider(), e.getMessage());
                }
                remote = gateway.fetchStatus(attempt.getProviderRef());
            }
        } catch (RuntimeException e) {
            log.warn("Could not re-check payment {}: {}", attempt.getId(), e.getMessage());
            return;
        }
        if (remote == PaymentStatus.PENDING) {
            log.info("Payment {} is still open at {}; trying again next run", attempt.getId(), attempt.getProvider());
            return;
        }
        statuses.apply(attempt.getId(), remote == PaymentStatus.CANCELLED ? PaymentStatus.EXPIRED : remote);
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

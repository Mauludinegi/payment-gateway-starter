package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.gateway.PaymentRequest;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final PaymentsProperties properties;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, PaymentAttemptRepository attempts, GatewayRegistry gateways,
                           PaymentStatusService statuses, PaymentsProperties properties, Clock clock) {
        this.orders = orders;
        this.attempts = attempts;
        this.gateways = gateways;
        this.statuses = statuses;
        this.properties = properties;
        this.clock = clock;
    }

    public Order createOrder(String description, long amount, String customerName) {
        Instant now = clock.instant();
        return orders.save(new Order(newReference(now), description, amount, customerName, now, now.plus(properties.orderTtl())));
    }

    /**
     * Starts (or switches to) a payment method. Pending attempts are cancelled first so the customer
     * cannot pay twice; if a cancelled one is paid anyway, the webhook still marks the order paid.
     * No transaction is held open while the gateway is called.
     */
    public OrderView startPayment(UUID orderId, Channel channel, String mobileNumber) {
        Order order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        if (!order.isPayable()) {
            throw new IllegalStateException("Order " + order.getReference() + " is " + order.getStatus());
        }
        PaymentGateway gateway = gateways.forChannel(channel);
        cancelPending(order);

        Instant now = clock.instant();
        Instant expiresAt = min(now.plus(properties.paymentTtl()), order.getExpiresAt());
        PaymentAttempt attempt = attempts.save(new PaymentAttempt(order, gateway.provider(), channel, now, expiresAt));

        GatewayPayment payment;
        try {
            payment = gateway.create(new PaymentRequest(attempt.getId(), order.getReference(), order.getDescription(),
                    order.getAmount(), order.getCustomerName(), mobileNumber, channel, expiresAt));
        } catch (GatewayException | IllegalArgumentException e) {
            statuses.apply(attempt.getId(), PaymentStatus.FAILED);
            throw e;
        }
        attempt.attachGatewayPayment(payment.providerRef(), payment.instruction(), payment.expiresAt(), clock.instant());
        attempts.save(attempt);
        return view(orderId);
    }

    public OrderView view(UUID orderId) {
        Optional<PaymentAttempt> latest = attempts.findLatestWithOrder(orderId);
        if (latest.isPresent()) {
            return new OrderView(latest.get().getOrder(), latest.get());
        }
        Order order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        return new OrderView(order, null);
    }

    public record OrderView(Order order, PaymentAttempt payment) {
    }

    private void cancelPending(Order order) {
        for (PaymentAttempt pending : attempts.findByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING)) {
            if (pending.getProviderRef() != null) {
                try {
                    gateways.get(pending.getProvider()).cancel(pending.getProviderRef());
                } catch (RuntimeException e) {
                    log.warn("Could not cancel payment {} at {}: {}", pending.getId(), pending.getProvider(), e.getMessage());
                }
            }
            statuses.apply(pending.getId(), PaymentStatus.CANCELLED);
        }
    }

    private static String newReference(Instant now) {
        StringBuilder sb = new StringBuilder("ORD-").append(REF_DATE.format(now)).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(REF_CHARS.charAt(RANDOM.nextInt(REF_CHARS.length())));
        }
        return sb.toString();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

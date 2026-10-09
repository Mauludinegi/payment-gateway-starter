package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.gateway.PaymentRequest;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderItem;
import io.github.mauludinegi.payments.order.OrderItemRepository;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_QUANTITY = 10;
    private static final long MIN_AMOUNT = 1_000;
    private static final long MAX_AMOUNT = 100_000_000;

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final PaymentAttemptRepository attempts;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final PaymentsProperties properties;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, OrderItemRepository items, ProductRepository products,
                           PaymentAttemptRepository attempts, GatewayRegistry gateways,
                           PaymentStatusService statuses, PaymentsProperties properties, Clock clock) {
        this.orders = orders;
        this.items = items;
        this.products = products;
        this.attempts = attempts;
        this.gateways = gateways;
        this.statuses = statuses;
        this.properties = properties;
        this.clock = clock;
    }

    public record CartLine(String productId, int quantity) {
    }

    /** Prices always come from the catalogue; the client only says what and how many. */
    @Transactional
    public OrderView createOrder(UUID userId, List<CartLine> lines, String customerName, String customerEmail) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (CartLine line : lines) {
            quantities.merge(line.productId(), line.quantity(), Integer::sum);
        }
        Map<String, Product> found = products.findAllById(quantities.keySet()).stream()
                .filter(Product::isActive)
                .collect(Collectors.toMap(Product::getId, p -> p));

        long amount = 0;
        for (var entry : quantities.entrySet()) {
            Product product = found.get(entry.getKey());
            if (product == null) {
                throw new IllegalArgumentException("Unknown product " + entry.getKey());
            }
            if (entry.getValue() < 1 || entry.getValue() > MAX_QUANTITY) {
                throw new IllegalArgumentException("Quantity of " + product.getName() + " must be 1 to " + MAX_QUANTITY);
            }
            amount += product.getPrice() * entry.getValue();
        }
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("Order total must be between IDR " + MIN_AMOUNT + " and " + MAX_AMOUNT);
        }

        Instant now = clock.instant();
        String first = found.get(quantities.keySet().iterator().next()).getName();
        String description = quantities.size() == 1 ? first : first + " + " + (quantities.size() - 1) + " more";
        Order order = orders.save(new Order(userId, newReference(now), description, amount, customerName, customerEmail,
                now, now.plus(properties.orderTtl())));
        List<OrderItem> saved = items.saveAll(quantities.entrySet().stream()
                .map(e -> {
                    Product p = found.get(e.getKey());
                    return new OrderItem(order.getId(), p.getId(), p.getName(), p.getPrice(), e.getValue());
                })
                .toList());
        return new OrderView(order, null, saved);
    }

    /**
     * Starts (or switches to) a payment method. Pending attempts are cancelled first so the customer
     * cannot pay twice; if a cancelled one is paid anyway, the webhook still marks the order paid.
     * No transaction is held open while the gateway is called.
     */
    public OrderView startPayment(UUID orderId, UUID userId, Channel channel, String mobileNumber) {
        Order order = orders.findById(orderId)
                .filter(o -> userId.equals(o.getUserId()))
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
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
            payment = gateway.create(new PaymentRequest(attempt.getId(), order.getId(), order.getReference(), order.getDescription(),
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
        Order order = latest.map(PaymentAttempt::getOrder)
                .or(() -> orders.findById(orderId))
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        return new OrderView(order, latest.orElse(null), items.findByOrderIdOrderById(orderId));
    }

    /** Someone else's order looks exactly like a missing one. */
    public OrderView viewOwned(UUID orderId, UUID userId) {
        OrderView view = view(orderId);
        if (!userId.equals(view.order().getUserId())) {
            throw new NotFoundException("Order " + orderId + " not found");
        }
        return view;
    }

    /** The customer's latest orders, newest first, each with its latest payment. */
    public List<OrderView> ordersOf(UUID userId) {
        List<Order> mine = orders.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        List<UUID> ids = mine.stream().map(Order::getId).toList();
        Map<UUID, PaymentAttempt> latest = new LinkedHashMap<>();
        for (PaymentAttempt a : attempts.findByOrderIdInOrderByCreatedAtDesc(ids)) {
            latest.putIfAbsent(a.getOrder().getId(), a);
        }
        Map<UUID, List<OrderItem>> itemsByOrder = items.findByOrderIdInOrderById(ids).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return mine.stream()
                .map(o -> new OrderView(o, latest.get(o.getId()), itemsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
    }

    public record OrderView(Order order, PaymentAttempt payment, List<OrderItem> items) {
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

package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.GatewayUnavailableException;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
    /** A repeated request (same key) retries an unconfirmed create only once the first try has had time to finish. */
    private static final Duration RETRY_CONFIRM_AFTER = Duration.ofSeconds(30);

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final PaymentAttemptRepository attempts;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final PaymentsProperties properties;
    private final ApplicationEventPublisher events;
    private final StockService stock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, OrderItemRepository items, ProductRepository products,
                           PaymentAttemptRepository attempts, GatewayRegistry gateways,
                           PaymentStatusService statuses, PaymentsProperties properties,
                           ApplicationEventPublisher events, StockService stock, TransactionTemplate tx,
                           Clock clock) {
        this.orders = orders;
        this.items = items;
        this.products = products;
        this.attempts = attempts;
        this.gateways = gateways;
        this.statuses = statuses;
        this.properties = properties;
        this.events = events;
        this.stock = stock;
        this.tx = tx;
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
        Map<Product, Integer> byProduct = new LinkedHashMap<>();
        quantities.forEach((id, quantity) -> byProduct.put(found.get(id), quantity));
        stock.hold(order, byProduct);
        List<OrderItem> saved = items.saveAll(quantities.entrySet().stream()
                .map(e -> {
                    Product p = found.get(e.getKey());
                    return new OrderItem(order.getId(), p.getId(), p.getName(), p.getPrice(), e.getValue());
                })
                .toList());
        return new OrderView(order, null, saved);
    }

    /**
     * Starts (or switches to) a payment method. The order row is locked while the new attempt is
     * recorded, so two clicks cannot both start one; the same {@code idempotencyKey} returns the
     * first attempt instead. Older pending attempts are cancelled so the customer cannot pay twice;
     * if a cancelled one is paid anyway, the webhook still marks the order paid. No transaction is
     * held open while the gateway is called.
     */
    public OrderView startPayment(UUID orderId, UUID userId, Channel channel, String mobileNumber, String idempotencyKey) {
        PaymentGateway gateway = gateways.forChannel(channel);
        Reservation reservation = tx.execute(status -> reserve(orderId, userId, gateway, channel, mobileNumber, idempotencyKey));
        if (!reservation.isNew()) {
            PaymentAttempt previous = attempts.findById(reservation.attemptId()).orElseThrow();
            if (previous.isUnconfirmed() && previous.getUpdatedAt().isBefore(clock.instant().minus(RETRY_CONFIRM_AFTER))) {
                confirmAtGateway(previous.getId(), true);
            }
            return view(orderId);
        }
        cancelAtGateway(reservation.superseded());
        confirmAtGateway(reservation.attemptId(), false);
        return view(orderId);
    }

    private record Reservation(UUID attemptId, List<UUID> superseded, boolean isNew) {
    }

    private Reservation reserve(UUID orderId, UUID userId, PaymentGateway gateway, Channel channel, String mobileNumber,
                                String idempotencyKey) {
        Order order = orders.findForUpdate(orderId)
                .filter(o -> userId.equals(o.getUserId()))
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        if (idempotencyKey != null) {
            Optional<PaymentAttempt> previous = attempts.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
            if (previous.isPresent()) {
                if (previous.get().getChannel() != channel) {
                    throw new IllegalArgumentException("This Idempotency-Key was already used for " + previous.get().getChannel());
                }
                return new Reservation(previous.get().getId(), List.of(), false);
            }
        }
        if (!order.isPayable()) {
            throw new IllegalStateException("Order " + order.getReference() + " is " + order.getStatus());
        }
        List<PaymentAttempt> pending = attempts.findByOrderIdAndStatus(orderId, PaymentStatus.PENDING);
        if (pending.stream().anyMatch(PaymentAttempt::isUnconfirmed)) {
            throw new PaymentInProgressException("The previous payment is still being confirmed with the gateway; try again in a moment");
        }
        Instant now = clock.instant();
        Instant expiresAt = min(now.plus(properties.paymentTtl()), order.getExpiresAt());
        PaymentAttempt attempt = attempts.save(new PaymentAttempt(order, gateway.provider(), channel, mobileNumber,
                idempotencyKey, now, expiresAt));
        return new Reservation(attempt.getId(), pending.stream().map(PaymentAttempt::getId).toList(), true);
    }

    /**
     * Creates the attempt's payment at the gateway. A timeout or gateway outage leaves the attempt
     * unconfirmed for {@link PaymentReconciler}; only a clear rejection marks it failed. A
     * {@code retry} gets back the payment made the first time where the gateway allows it (the
     * attempt id is the Midtrans order id), and is skipped where a second one could be paid too.
     */
    public void confirmAtGateway(UUID attemptId, boolean retry) {
        PaymentAttempt attempt = attempts.findWithOrder(attemptId).orElseThrow();
        if (!attempt.isUnconfirmed()) {
            return;
        }
        PaymentGateway gateway = gateways.get(attempt.getProvider());
        if (retry && !gateway.canRetryCreate(attempt.getChannel())) {
            return;
        }
        Order order = attempt.getOrder();
        GatewayPayment payment;
        try {
            payment = gateway.create(new PaymentRequest(attempt.getId(), order.getId(),
                    order.getReference(), order.getDescription(), order.getAmount(), order.getCustomerName(),
                    attempt.getMobileNumber(), attempt.getChannel(), attempt.getExpiresAt()));
        } catch (GatewayUnavailableException e) {
            log.warn("Payment {} not confirmed by {}, will retry: {}", attemptId, attempt.getProvider(), e.getMessage());
            tx.executeWithoutResult(status -> attempts.findById(attemptId)
                    .ifPresent(a -> a.recordUnconfirmed(e.getMessage(), clock.instant())));
            events.publishEvent(new OrderChangedEvent(order.getId()));
            return;
        } catch (GatewayException | IllegalArgumentException e) {
            statuses.apply(attemptId, PaymentStatus.FAILED);
            throw e;
        }
        tx.executeWithoutResult(status -> attempts.findById(attemptId).ifPresent(a ->
                a.attachGatewayPayment(payment.providerRef(), payment.instruction(), payment.expiresAt(), clock.instant())));
        events.publishEvent(new OrderChangedEvent(order.getId()));
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

    private void cancelAtGateway(List<UUID> attemptIds) {
        for (UUID id : attemptIds) {
            PaymentAttempt pending = attempts.findById(id).orElseThrow();
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

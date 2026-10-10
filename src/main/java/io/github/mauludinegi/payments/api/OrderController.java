package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.auth.AuthService;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserAuth;
import io.github.mauludinegi.payments.catalog.CatalogService;
import io.github.mauludinegi.payments.catalog.CatalogService.CatalogProduct;
import io.github.mauludinegi.payments.service.NotFoundException;
import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderItem;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.service.CheckoutService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OrderController {

    private static final java.util.regex.Pattern IDEMPOTENCY_KEY = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final CheckoutService checkout;
    private final GatewayRegistry gateways;
    private final CatalogService catalog;
    private final AuthService auth;
    private final OrderEventHub events;
    private final PaymentsProperties properties;

    public OrderController(CheckoutService checkout, GatewayRegistry gateways, CatalogService catalog, AuthService auth,
                           OrderEventHub events, PaymentsProperties properties) {
        this.checkout = checkout;
        this.gateways = gateways;
        this.catalog = catalog;
        this.auth = auth;
        this.events = events;
        this.properties = properties;
    }

    /** The receipt goes to the account's email; the name defaults to the account's but can be changed. */
    public record CreateOrder(
            @NotEmpty @Size(max = 20) List<@Valid Line> items,
            @Size(max = 100) String customerName) {

        public record Line(@NotBlank String productId, @Min(1) @Max(10) int quantity) {
        }
    }

    public record StartPayment(
            @NotNull Channel channel,
            @Pattern(regexp = "^\\+62\\d{8,13}$", message = "must look like +6281234567890") String mobileNumber) {
    }

    public record ChannelOption(Channel channel, String label, Channel.Kind kind, String provider) {
    }

    @GetMapping("/products")
    public List<CatalogProduct> products() {
        return catalog.products();
    }

    @GetMapping("/products/{id}")
    public CatalogProduct product(@PathVariable String id) {
        return catalog.product(id).orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }

    @GetMapping("/channels")
    public List<ChannelOption> channels() {
        return gateways.availableChannels().entrySet().stream()
                .map(e -> new ChannelOption(e.getKey(), e.getKey().label(), e.getKey().kind(), e.getValue().name()))
                .toList();
    }

    public record Environment(boolean sandbox, Map<Provider, Boolean> testMode) {
    }

    /** {@code sandbox} is true only when every provider taking payments uses test credentials. */
    @GetMapping("/environment")
    public Environment environment() {
        Map<Provider, Boolean> testMode = new EnumMap<>(Provider.class);
        for (Provider provider : gateways.availableChannels().values()) {
            testMode.put(provider, properties.isTestMode(provider));
        }
        return new Environment(testMode.values().stream().allMatch(Boolean::booleanValue), testMode);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@RequestAttribute(UserAuth.USER_ID) UUID userId, @Valid @RequestBody CreateOrder body) {
        User user = auth.user(userId);
        List<CheckoutService.CartLine> lines = body.items().stream()
                .map(l -> new CheckoutService.CartLine(l.productId(), l.quantity()))
                .toList();
        String name = body.customerName() == null || body.customerName().isBlank() ? user.getName() : body.customerName().trim();
        return OrderResponse.of(checkout.createOrder(userId, lines, name, user.getEmail()));
    }

    /** Polled by the payment page every few seconds. */
    @GetMapping("/orders/{id}")
    public OrderResponse get(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable UUID id) {
        return OrderResponse.of(checkout.viewOwned(id, userId));
    }

    /** Server-Sent Events: an {@code order} event now and after every change, until the order is paid or expired. */
    @GetMapping(path = "/orders/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable UUID id,
                             HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        return events.subscribe(id, userId);
    }

    /**
     * Send the same {@code Idempotency-Key} when retrying a click or a timed-out request: it returns the
     * payment the first request started instead of starting another. 202 means the gateway has not
     * confirmed the payment yet; poll the order until {@code payment.confirming} is false.
     */
    @PostMapping("/orders/{id}/payments")
    public ResponseEntity<OrderResponse> pay(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable UUID id,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                             @Valid @RequestBody StartPayment body) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 64 letters, digits, '-' or '_'");
        }
        OrderResponse order = OrderResponse.of(checkout.startPayment(id, userId, body.channel(), body.mobileNumber(), idempotencyKey));
        boolean confirming = order.payment() != null && order.payment().confirming();
        return ResponseEntity.status(confirming ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(order);
    }

    @GetMapping("/me/orders")
    public List<OrderResponse> myOrders(@RequestAttribute(UserAuth.USER_ID) UUID userId) {
        return checkout.ordersOf(userId).stream().map(OrderResponse::of).toList();
    }

    public record OrderResponse(
            UUID id, String reference, String description, long amount, String customerName, String customerEmail,
            String status, Instant createdAt, Instant expiresAt, Instant paidAt, List<ItemResponse> items, PaymentResponse payment) {

        static OrderResponse of(CheckoutService.OrderView view) {
            Order o = view.order();
            PaymentAttempt a = view.payment();
            return new OrderResponse(o.getId(), o.getReference(), o.getDescription(), o.getAmount(), o.getCustomerName(),
                    o.getCustomerEmail(), o.getStatus().name(), o.getCreatedAt(), o.getExpiresAt(), o.getPaidAt(),
                    view.items().stream().map(ItemResponse::of).toList(), a == null ? null : PaymentResponse.of(a));
        }
    }

    public record ItemResponse(String productId, String name, long unitPrice, int quantity, long total) {

        static ItemResponse of(OrderItem i) {
            return new ItemResponse(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(), i.lineTotal());
        }
    }

    public record PaymentResponse(
            UUID id, String provider, Channel channel, String channelLabel, Channel.Kind kind, String status,
            String instructionType, String instructionValue, boolean confirming, Instant createdAt, Instant expiresAt) {

        static PaymentResponse of(PaymentAttempt a) {
            var instruction = a.instruction();
            return new PaymentResponse(a.getId(), a.getProvider().name(), a.getChannel(), a.getChannel().label(),
                    a.getChannel().kind(), a.getStatus().name(),
                    instruction == null ? null : instruction.type().name(), instruction == null ? null : instruction.value(),
                    a.isUnconfirmed(), a.getCreatedAt(), a.getExpiresAt());
        }
    }
}

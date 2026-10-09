package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.auth.AuthService;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserAuth;
import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderItem;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.service.CheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final CheckoutService checkout;
    private final GatewayRegistry gateways;
    private final ProductRepository products;
    private final AuthService auth;

    public OrderController(CheckoutService checkout, GatewayRegistry gateways, ProductRepository products, AuthService auth) {
        this.checkout = checkout;
        this.gateways = gateways;
        this.products = products;
        this.auth = auth;
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

    public record ProductResponse(String id, String name, String description, String category, String icon, long price) {

        static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getCategory(), p.getIcon(), p.getPrice());
        }
    }

    @GetMapping("/products")
    public List<ProductResponse> products() {
        return products.findByActiveTrueOrderBySortOrder().stream().map(ProductResponse::of).toList();
    }

    @GetMapping("/channels")
    public List<ChannelOption> channels() {
        return gateways.availableChannels().entrySet().stream()
                .map(e -> new ChannelOption(e.getKey(), e.getKey().label(), e.getKey().kind(), e.getValue().name()))
                .toList();
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

    @PostMapping("/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse pay(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable UUID id,
                             @Valid @RequestBody StartPayment body) {
        return OrderResponse.of(checkout.startPayment(id, userId, body.channel(), body.mobileNumber()));
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
            String instructionType, String instructionValue, Instant createdAt, Instant expiresAt) {

        static PaymentResponse of(PaymentAttempt a) {
            var instruction = a.instruction();
            return new PaymentResponse(a.getId(), a.getProvider().name(), a.getChannel(), a.getChannel().label(),
                    a.getChannel().kind(), a.getStatus().name(),
                    instruction == null ? null : instruction.type().name(), instruction == null ? null : instruction.value(),
                    a.getCreatedAt(), a.getExpiresAt());
        }
    }
}

package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.service.CheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public OrderController(CheckoutService checkout, GatewayRegistry gateways) {
        this.checkout = checkout;
        this.gateways = gateways;
    }

    public record CreateOrder(
            @NotBlank @Size(max = 255) String description,
            @Min(1_000) @Max(100_000_000) long amount,
            @NotBlank @Size(max = 100) String customerName) {
    }

    public record StartPayment(
            @NotNull Channel channel,
            @Pattern(regexp = "^\\+62\\d{8,13}$", message = "must look like +6281234567890") String mobileNumber) {
    }

    public record ChannelOption(Channel channel, String label, Channel.Kind kind, String provider) {
    }

    @GetMapping("/channels")
    public List<ChannelOption> channels() {
        return gateways.availableChannels().entrySet().stream()
                .map(e -> new ChannelOption(e.getKey(), e.getKey().label(), e.getKey().kind(), e.getValue().name()))
                .toList();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrder body) {
        Order order = checkout.createOrder(body.description(), body.amount(), body.customerName());
        return OrderResponse.of(order, null);
    }

    /** Polled by the payment page every few seconds. */
    @GetMapping("/orders/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        CheckoutService.OrderView view = checkout.view(id);
        return OrderResponse.of(view.order(), view.payment());
    }

    @PostMapping("/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse pay(@PathVariable UUID id, @Valid @RequestBody StartPayment body) {
        CheckoutService.OrderView view = checkout.startPayment(id, body.channel(), body.mobileNumber());
        return OrderResponse.of(view.order(), view.payment());
    }

    public record OrderResponse(
            UUID id, String reference, String description, long amount, String customerName,
            String status, Instant expiresAt, Instant paidAt, PaymentResponse payment) {

        static OrderResponse of(Order o, PaymentAttempt a) {
            return new OrderResponse(o.getId(), o.getReference(), o.getDescription(), o.getAmount(), o.getCustomerName(),
                    o.getStatus().name(), o.getExpiresAt(), o.getPaidAt(), a == null ? null : PaymentResponse.of(a));
        }
    }

    public record PaymentResponse(
            UUID id, String provider, Channel channel, String channelLabel, String status,
            String instructionType, String instructionValue, Instant expiresAt) {

        static PaymentResponse of(PaymentAttempt a) {
            var instruction = a.instruction();
            return new PaymentResponse(a.getId(), a.getProvider().name(), a.getChannel(), a.getChannel().label(), a.getStatus().name(),
                    instruction == null ? null : instruction.type().name(), instruction == null ? null : instruction.value(), a.getExpiresAt());
        }
    }
}

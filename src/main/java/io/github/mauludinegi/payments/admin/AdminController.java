package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderStatus;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.PaymentStatusService;
import io.github.mauludinegi.payments.webhook.WebhookEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    /** Lets the web app check a token before storing it. */
    @GetMapping("/session")
    public void session() {
    }

    @GetMapping("/stats")
    public AdminService.Stats stats() {
        return admin.stats();
    }

    @GetMapping("/orders")
    public AdminService.PageResult<AdminService.OrderRow> orders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin.orders(status, q, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/orders/{id}")
    public OrderDetailResponse order(@PathVariable UUID id) {
        return OrderDetailResponse.of(admin.order(id));
    }

    @GetMapping("/webhooks")
    public AdminService.PageResult<AdminService.WebhookRow> webhooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin.webhooks(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @PostMapping("/payments/{id}/sync")
    public AdminService.SyncResult sync(@PathVariable UUID id) {
        return admin.sync(id);
    }

    public record OrderDetailResponse(
            UUID id, String reference, String description, long amount, String customerName, String customerEmail,
            OrderStatus status, Instant createdAt, Instant expiresAt, Instant paidAt, boolean needsRefund,
            List<Item> items, List<Payment> payments, List<Webhook> webhooks) {

        static OrderDetailResponse of(AdminService.OrderDetail d) {
            Order o = d.order();
            return new OrderDetailResponse(o.getId(), o.getReference(), o.getDescription(), o.getAmount(),
                    o.getCustomerName(), o.getCustomerEmail(), o.getStatus(), o.getCreatedAt(), o.getExpiresAt(),
                    o.getPaidAt(), d.needsRefund(),
                    d.items().stream().map(i -> new Item(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(), i.lineTotal())).toList(),
                    d.payments().stream().map(Payment::of).toList(),
                    d.webhooks().stream().map(Webhook::of).toList());
        }
    }

    public record Item(String productId, String name, long unitPrice, int quantity, long total) {
    }

    public record Payment(UUID id, Provider provider, Channel channel, String channelLabel, Channel.Kind kind,
                          PaymentStatus status, String providerRef, String instructionType, String instructionValue,
                          Instant createdAt, Instant updatedAt, Instant expiresAt) {

        static Payment of(PaymentAttempt a) {
            var instruction = a.instruction();
            return new Payment(a.getId(), a.getProvider(), a.getChannel(), a.getChannel().label(), a.getChannel().kind(),
                    a.getStatus(), a.getProviderRef(),
                    instruction == null ? null : instruction.type().name(), instruction == null ? null : instruction.value(),
                    a.getCreatedAt(), a.getUpdatedAt(), a.getExpiresAt());
        }
    }

    public record Webhook(Long id, Provider provider, String eventKey, Instant receivedAt, UUID paymentId,
                          PaymentStatus confirmedStatus, PaymentStatusService.Outcome outcome) {

        static Webhook of(WebhookEvent e) {
            return new Webhook(e.getId(), e.getProvider(), e.getEventKey(), e.getReceivedAt(), e.getAttemptId(),
                    e.getConfirmedStatus(), e.getOutcome());
        }
    }
}

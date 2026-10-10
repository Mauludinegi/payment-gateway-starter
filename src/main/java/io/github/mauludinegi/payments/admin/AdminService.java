package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderItem;
import io.github.mauludinegi.payments.order.OrderItemRepository;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.order.OrderStatus;
import io.github.mauludinegi.payments.order.StatusTotal;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.NotFoundException;
import io.github.mauludinegi.payments.service.PaymentStatusService;
import io.github.mauludinegi.payments.webhook.WebhookEvent;
import io.github.mauludinegi.payments.webhook.WebhookEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read models for the admin dashboard, plus a manual re-check of a payment with its gateway. */
@Service
@Transactional(readOnly = true)
public class AdminService {

    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final PaymentAttemptRepository attempts;
    private final WebhookEventRepository events;
    private final GatewayRegistry gateways;
    private final PaymentStatusService statuses;
    private final Clock clock;

    public AdminService(OrderRepository orders, OrderItemRepository items, PaymentAttemptRepository attempts,
                        WebhookEventRepository events, GatewayRegistry gateways, PaymentStatusService statuses, Clock clock) {
        this.orders = orders;
        this.items = items;
        this.attempts = attempts;
        this.events = events;
        this.gateways = gateways;
        this.statuses = statuses;
        this.clock = clock;
    }

    public record Stats(long orders, long paid, long pending, long expired, long revenue, double conversionRate,
                        int paidTwice, List<Day> last7Days, List<ChannelStat> channels) {
    }

    public record Day(LocalDate date, long paid, long revenue) {
    }

    public record ChannelStat(Channel channel, String label, Channel.Kind kind, long payments, long amount) {
    }

    public Stats stats() {
        Map<OrderStatus, StatusTotal> totals = orders.totalsByStatus().stream()
                .collect(Collectors.toMap(StatusTotal::status, Function.identity()));
        long all = totals.values().stream().mapToLong(StatusTotal::count).sum();
        StatusTotal paid = totals.getOrDefault(OrderStatus.PAID, new StatusTotal(OrderStatus.PAID, 0, 0));

        LocalDate today = LocalDate.now(clock.withZone(JAKARTA));
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            byDay.put(today.minusDays(i), new long[2]);
        }
        Instant since = today.minusDays(6).atStartOfDay(JAKARTA).toInstant();
        for (Order o : orders.findByPaidAtGreaterThanEqual(since)) {
            long[] day = byDay.get(LocalDate.ofInstant(o.getPaidAt(), JAKARTA));
            if (day != null) {
                day[0]++;
                day[1] += o.getAmount();
            }
        }

        List<ChannelStat> channels = attempts.succeededByChannel().stream()
                .map(c -> new ChannelStat(c.channel(), c.channel().label(), c.channel().kind(), c.count(), c.amount()))
                .sorted(Comparator.comparingLong(ChannelStat::amount).reversed())
                .toList();

        return new Stats(all, paid.count(), count(totals, OrderStatus.PENDING_PAYMENT), count(totals, OrderStatus.EXPIRED),
                paid.amount(), all == 0 ? 0 : (double) paid.count() / all, attempts.findOrdersPaidTwice().size(),
                byDay.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue()[0], e.getValue()[1])).toList(),
                channels);
    }

    public record PageResult<T>(List<T> items, int page, int size, long total) {
    }

    public record OrderRow(UUID id, String reference, String description, String customerName, String customerEmail,
                           long amount, OrderStatus status, Instant createdAt, Instant paidAt,
                           Channel channel, String channelLabel, PaymentStatus paymentStatus, Provider provider) {
    }

    public PageResult<OrderRow> orders(OrderStatus status, String q, int page, int size) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), like),
                        cb.like(cb.lower(root.get("customerName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("customerEmail"), "")), like)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<Order> result = orders.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<UUID, PaymentAttempt> latest = new HashMap<>();
        List<UUID> ids = result.getContent().stream().map(Order::getId).toList();
        if (!ids.isEmpty()) {
            for (PaymentAttempt a : attempts.findByOrderIdInOrderByCreatedAtDesc(ids)) {
                latest.putIfAbsent(a.getOrder().getId(), a);
            }
        }
        List<OrderRow> rows = result.getContent().stream().map(o -> {
            PaymentAttempt a = latest.get(o.getId());
            return new OrderRow(o.getId(), o.getReference(), o.getDescription(), o.getCustomerName(), o.getCustomerEmail(),
                    o.getAmount(), o.getStatus(), o.getCreatedAt(), o.getPaidAt(),
                    a == null ? null : a.getChannel(), a == null ? null : a.getChannel().label(),
                    a == null ? null : a.getStatus(), a == null ? null : a.getProvider());
        }).toList();
        return new PageResult<>(rows, page, size, result.getTotalElements());
    }

    public record OrderDetail(Order order, List<OrderItem> items, List<PaymentAttempt> payments, List<WebhookEvent> webhooks,
                              boolean needsRefund) {
    }

    public OrderDetail order(UUID id) {
        Order order = orders.findById(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
        List<PaymentAttempt> payments = attempts.findByOrderIdOrderByCreatedAtDesc(id);
        List<WebhookEvent> webhooks = payments.isEmpty() ? List.of()
                : events.findByAttemptIdInOrderByIdDesc(payments.stream().map(PaymentAttempt::getId).toList());
        long succeeded = payments.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).count();
        return new OrderDetail(order, items.findByOrderIdOrderById(id), payments, webhooks, succeeded > 1);
    }

    public record WebhookRow(Long id, Provider provider, String eventKey, Instant receivedAt, UUID paymentId,
                             UUID orderId, String orderReference, Channel channel,
                             PaymentStatus confirmedStatus, PaymentStatusService.Outcome outcome,
                             String providerRef, boolean queued) {
    }

    public PageResult<WebhookRow> webhooks(int page, int size) {
        Page<WebhookEvent> result = events.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        List<UUID> attemptIds = result.getContent().stream().map(WebhookEvent::getAttemptId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, PaymentAttempt> byId = attemptIds.isEmpty() ? Map.of()
                : attempts.findWithOrderByIdIn(attemptIds).stream().collect(Collectors.toMap(PaymentAttempt::getId, Function.identity()));
        List<WebhookRow> rows = result.getContent().stream().map(e -> {
            PaymentAttempt a = e.getAttemptId() == null ? null : byId.get(e.getAttemptId());
            return new WebhookRow(e.getId(), e.getProvider(), e.getEventKey(), e.getReceivedAt(), e.getAttemptId(),
                    a == null ? null : a.getOrder().getId(), a == null ? null : a.getOrder().getReference(),
                    a == null ? null : a.getChannel(), e.getConfirmedStatus(), e.getOutcome(),
                    e.getProviderRef(), e.isReplayPending());
        }).toList();
        return new PageResult<>(rows, page, size, result.getTotalElements());
    }

    public record SyncResult(PaymentStatus status, PaymentStatusService.Outcome outcome) {
    }

    /** Asks the gateway for the current status and applies it, for when a webhook was missed. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SyncResult sync(UUID paymentId) {
        PaymentAttempt attempt = attempts.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        if (attempt.getProviderRef() == null) {
            throw new IllegalStateException("Payment " + paymentId + " was never created at the gateway");
        }
        PaymentStatus status = gateways.get(attempt.getProvider()).fetchStatus(attempt.getProviderRef());
        return new SyncResult(status, statuses.apply(paymentId, status));
    }

    private static long count(Map<OrderStatus, StatusTotal> totals, OrderStatus status) {
        StatusTotal t = totals.get(status);
        return t == null ? 0 : t.count();
    }
}

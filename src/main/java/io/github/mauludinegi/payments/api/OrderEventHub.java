package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.order.OrderStatus;
import io.github.mauludinegi.payments.service.CheckoutService;
import io.github.mauludinegi.payments.service.OrderChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes an order to its open payment pages (Server-Sent Events) whenever it changes, instead of having
 * every page poll. Connections live in this instance's memory; with several instances, a page connected
 * elsewhere misses the push and the web app's fallback polling picks the change up.
 */
@Component
public class OrderEventHub {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHub.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_STREAMS_PER_ORDER = 10;

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final CheckoutService checkout;

    public OrderEventHub(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /** Opens a stream for the order's owner and sends the current state right away. */
    public SseEmitter subscribe(UUID orderId, UUID userId) {
        OrderController.OrderResponse current = OrderController.OrderResponse.of(checkout.viewOwned(orderId, userId));
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        if (!send(emitter, current)) {
            return emitter;
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(current.status())) {
            emitter.complete();
            return emitter;
        }
        Set<SseEmitter> forOrder = streams.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet());
        if (forOrder.size() >= MAX_STREAMS_PER_ORDER) {
            emitter.complete();
            return emitter;
        }
        forOrder.add(emitter);
        Runnable remove = () -> remove(orderId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** Runs after the change is committed, off the webhook's thread, so a slow browser never delays the gateway. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onOrderChanged(OrderChangedEvent event) {
        Set<SseEmitter> forOrder = streams.get(event.orderId());
        if (forOrder == null || forOrder.isEmpty()) {
            return;
        }
        OrderController.OrderResponse order = OrderController.OrderResponse.of(checkout.view(event.orderId()));
        boolean finished = !OrderStatus.PENDING_PAYMENT.name().equals(order.status());
        for (SseEmitter emitter : forOrder) {
            if (send(emitter, order) && finished) {
                emitter.complete();
            }
        }
    }

    /** Keeps idle connections open through proxies and load balancers that drop silent ones. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        streams.forEach((orderId, forOrder) -> forOrder.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                remove(orderId, emitter);
            }
        }));
    }

    /**
     * Graceful shutdown waits for open requests, and these never end on their own. Closing them first lets the
     * instance stop right away; browsers reconnect to another one.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        streams.values().forEach(forOrder -> forOrder.forEach(SseEmitter::complete));
        streams.clear();
    }

    int openStreams(UUID orderId) {
        Set<SseEmitter> forOrder = streams.get(orderId);
        return forOrder == null ? 0 : forOrder.size();
    }

    private boolean send(SseEmitter emitter, OrderController.OrderResponse order) {
        try {
            emitter.send(SseEmitter.event().name("order").data(order));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping order stream: {}", e.getMessage());
            emitter.completeWithError(e);
            return false;
        }
    }

    private void remove(UUID orderId, SseEmitter emitter) {
        streams.computeIfPresent(orderId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}

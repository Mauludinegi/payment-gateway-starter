package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.catalog.CatalogChangedEvent;
import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderItem;
import io.github.mauludinegi.payments.order.OrderItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Limited stock is held from checkout until the order is paid or expires, so a customer who is paying never finds
 * the product gone. All changes run inside the caller's transaction: a failed checkout gives everything back.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductRepository products;
    private final OrderItemRepository items;
    private final ApplicationEventPublisher events;

    public StockService(ProductRepository products, OrderItemRepository items, ApplicationEventPublisher events) {
        this.products = products;
        this.items = items;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(Order order, Map<Product, Integer> quantities) {
        boolean held = false;
        for (var line : quantities.entrySet()) {
            Product product = line.getKey();
            if (product.getStock() == null) {
                continue;
            }
            if (products.takeStock(product.getId(), line.getValue()) == 0) {
                throw new OutOfStockException(product.getName(), products.stockOf(product.getId()));
            }
            held = true;
        }
        if (held) {
            order.holdStock();
            events.publishEvent(new CatalogChangedEvent());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Order order) {
        if (!order.isStockHeld()) {
            return;
        }
        for (OrderItem item : items.findByOrderIdOrderById(order.getId())) {
            products.returnStock(item.getProductId(), item.getQuantity());
        }
        order.releaseStock();
        events.publishEvent(new CatalogChangedEvent());
    }

    /**
     * An expired order was paid after all, and its stock had gone back on sale. Takes it again where possible;
     * otherwise the order stays paid (the money arrived) and is flagged for the admin to restock or refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retakeForLatePayment(Order order) {
        if (order.isStockHeld()) {
            return;
        }
        List<OrderItem> lines = items.findByOrderIdOrderById(order.getId());
        Set<String> limited = products.findAllById(lines.stream().map(OrderItem::getProductId).toList()).stream()
                .filter(p -> p.getStock() != null)
                .map(Product::getId)
                .collect(Collectors.toSet());
        if (limited.isEmpty()) {
            return;
        }
        for (OrderItem item : lines) {
            if (!limited.contains(item.getProductId())) {
                continue;
            }
            if (products.takeStock(item.getProductId(), item.getQuantity()) > 0) {
                order.holdStock();
            } else {
                order.markStockShort();
                log.warn("Order {} was paid late and {} x {} is no longer in stock; restock or refund it",
                        order.getReference(), item.getQuantity(), item.getProductName());
            }
        }
        events.publishEvent(new CatalogChangedEvent());
    }
}

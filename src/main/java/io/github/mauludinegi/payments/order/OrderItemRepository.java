package io.github.mauludinegi.payments.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderById(UUID orderId);

    List<OrderItem> findByOrderIdInOrderById(Collection<UUID> orderIds);
}

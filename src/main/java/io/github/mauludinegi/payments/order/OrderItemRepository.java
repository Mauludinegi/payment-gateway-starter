package io.github.mauludinegi.payments.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderById(UUID orderId);

    List<OrderItem> findByOrderIdInOrderById(Collection<UUID> orderIds);

    @Query("""
            select count(i) > 0 from OrderItem i
            where i.productId = :productId
              and i.orderId in (select o.id from Order o
                                where o.userId = :userId and o.status = io.github.mauludinegi.payments.order.OrderStatus.PAID)
            """)
    boolean hasPaidFor(@Param("userId") UUID userId, @Param("productId") String productId);
}

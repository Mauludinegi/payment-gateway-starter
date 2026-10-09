package io.github.mauludinegi.payments.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    @Query("""
            select o from Order o
            where o.status = io.github.mauludinegi.payments.order.OrderStatus.PENDING_PAYMENT
              and o.expiresAt < :now
              and not exists (
                select a from PaymentAttempt a
                where a.order = o and a.status = io.github.mauludinegi.payments.payment.PaymentStatus.PENDING)
            """)
    List<Order> findExpiredWithoutPendingPayment(Instant now);

    @Query("""
            select new io.github.mauludinegi.payments.order.StatusTotal(o.status, count(o), coalesce(sum(o.amount), 0))
            from Order o group by o.status
            """)
    List<StatusTotal> totalsByStatus();

    List<Order> findByPaidAtGreaterThanEqual(Instant since);

    List<Order> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}

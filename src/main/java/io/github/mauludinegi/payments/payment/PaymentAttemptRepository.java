package io.github.mauludinegi.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    /** The status endpoint polled by the payment page: one query, order included. */
    @Query("""
            select a from PaymentAttempt a join fetch a.order
            where a.order.id = :orderId
            order by a.createdAt desc
            limit 1
            """)
    Optional<PaymentAttempt> findLatestWithOrder(UUID orderId);

    @Query("select a from PaymentAttempt a join fetch a.order where a.id = :id")
    Optional<PaymentAttempt> findWithOrder(UUID id);

    @Query("select a from PaymentAttempt a join fetch a.order where a.provider = :provider and a.providerRef = :providerRef")
    Optional<PaymentAttempt> findWithOrderByProviderRef(Provider provider, String providerRef);

    List<PaymentAttempt> findByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    List<PaymentAttempt> findByStatusAndExpiresAtBefore(PaymentStatus status, Instant before);
}

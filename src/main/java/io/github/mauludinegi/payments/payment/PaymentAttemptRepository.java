package io.github.mauludinegi.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
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

    Optional<PaymentAttempt> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    /** Attempts whose create call the gateway has not confirmed (timed out, or still in flight). */
    @Query("""
            select a.id from PaymentAttempt a
            where a.status = io.github.mauludinegi.payments.payment.PaymentStatus.PENDING
              and a.providerRef is null and a.updatedAt < :before
            """)
    List<UUID> findUnconfirmedIds(Instant before);

    List<PaymentAttempt> findByStatusAndExpiresAtBefore(PaymentStatus status, Instant before);

    List<PaymentAttempt> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    List<PaymentAttempt> findByOrderIdInOrderByCreatedAtDesc(Collection<UUID> orderIds);

    @Query("select a from PaymentAttempt a join fetch a.order where a.id in :ids")
    List<PaymentAttempt> findWithOrderByIdIn(Collection<UUID> ids);

    @Query("""
            select new io.github.mauludinegi.payments.payment.ChannelTotal(a.channel, count(a), coalesce(sum(a.order.amount), 0))
            from PaymentAttempt a
            where a.status = io.github.mauludinegi.payments.payment.PaymentStatus.SUCCEEDED
            group by a.channel
            """)
    List<ChannelTotal> succeededByChannel();

    /** Orders paid more than once (e.g. an old VA paid after switching to QRIS); the extra payment needs a refund. */
    @Query("""
            select a.order.id from PaymentAttempt a
            where a.status = io.github.mauludinegi.payments.payment.PaymentStatus.SUCCEEDED
            group by a.order.id having count(a) > 1
            """)
    List<UUID> findOrdersPaidTwice();
}

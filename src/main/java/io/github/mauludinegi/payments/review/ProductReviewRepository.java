package io.github.mauludinegi.payments.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID>, JpaSpecificationExecutor<ProductReview> {

    Optional<ProductReview> findByProductIdAndUserId(String productId, UUID userId);

    Page<ProductReview> findByProductIdAndHiddenFalse(String productId, Pageable pageable);

    @Query("""
            select new io.github.mauludinegi.payments.review.RatingSummary(r.productId, avg(r.rating), count(r))
            from ProductReview r
            where r.hidden = false
            group by r.productId
            """)
    List<RatingSummary> summaries();
}

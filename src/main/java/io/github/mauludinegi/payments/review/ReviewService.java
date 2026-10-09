package io.github.mauludinegi.payments.review;

import io.github.mauludinegi.payments.admin.AdminService.PageResult;
import io.github.mauludinegi.payments.auth.ForbiddenException;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserRepository;
import io.github.mauludinegi.payments.catalog.CatalogChangedEvent;
import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.order.OrderItemRepository;
import io.github.mauludinegi.payments.service.NotFoundException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Only customers with a paid order for a product may review it, once; they can edit or delete their review. */
@Service
public class ReviewService {

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ProductReviewRepository reviews;
    private final ProductRepository products;
    private final OrderItemRepository items;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReviewService(ProductReviewRepository reviews, ProductRepository products, OrderItemRepository items,
                         UserRepository users, ApplicationEventPublisher events, Clock clock) {
        this.reviews = reviews;
        this.products = products;
        this.items = items;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /** What other shoppers see: a first name and last initial, never the email. */
    public record PublicReview(UUID id, String author, String authorPictureUrl, int rating, String comment,
                               Instant createdAt, Instant updatedAt) {
    }

    /** {@code hidden} lets the author see that a moderator hid their review; others never see it. */
    public record MyReview(boolean canReview, PublicReview review, boolean hidden) {
    }

    public record AdminReview(UUID id, String productId, String productName, String authorName, String authorEmail,
                              int rating, String comment, boolean hidden, Instant createdAt, Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public PageResult<PublicReview> visible(String productId, int page, int size) {
        product(productId);
        Page<ProductReview> result = reviews.findByProductIdAndHiddenFalse(productId, PageRequest.of(page, size, NEWEST));
        Map<UUID, User> authors = usersOf(result.getContent());
        List<PublicReview> rows = result.getContent().stream().map(r -> toPublic(r, authors.get(r.getUserId()))).toList();
        return new PageResult<>(rows, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MyReview mine(UUID userId, String productId) {
        product(productId);
        Optional<ProductReview> review = reviews.findByProductIdAndUserId(productId, userId);
        User me = users.findById(userId).orElse(null);
        return new MyReview(items.hasPaidFor(userId, productId), review.map(r -> toPublic(r, me)).orElse(null),
                review.map(ProductReview::isHidden).orElse(false));
    }

    @Transactional
    public PublicReview save(UUID userId, String productId, int rating, String comment) {
        product(productId);
        if (!items.hasPaidFor(userId, productId)) {
            throw new ForbiddenException("Only customers who bought this product can review it");
        }
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        Instant now = clock.instant();
        ProductReview review = reviews.findByProductIdAndUserId(productId, userId)
                .map(existing -> {
                    existing.edit(rating, text, now);
                    return existing;
                })
                .orElseGet(() -> reviews.save(new ProductReview(productId, userId, rating, text, now)));
        events.publishEvent(new CatalogChangedEvent());
        return toPublic(review, users.findById(userId).orElse(null));
    }

    @Transactional
    public void delete(UUID userId, String productId) {
        reviews.findByProductIdAndUserId(productId, userId).ifPresent(review -> {
            reviews.delete(review);
            events.publishEvent(new CatalogChangedEvent());
        });
    }

    @Transactional(readOnly = true)
    public PageResult<AdminReview> forAdmin(String productId, Boolean hidden, int page, int size) {
        Specification<ProductReview> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (productId != null && !productId.isBlank()) {
                where.add(cb.equal(root.get("productId"), productId));
            }
            if (hidden != null) {
                where.add(cb.equal(root.get("hidden"), hidden));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<ProductReview> result = reviews.findAll(spec, PageRequest.of(page, size, NEWEST));
        Map<UUID, User> authors = usersOf(result.getContent());
        Map<String, String> names = products.findAllById(result.getContent().stream().map(ProductReview::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getName));
        List<AdminReview> rows = result.getContent().stream().map(r -> {
            User author = authors.get(r.getUserId());
            return new AdminReview(r.getId(), r.getProductId(), names.get(r.getProductId()),
                    author == null ? null : author.getName(), author == null ? null : author.getEmail(),
                    r.getRating(), r.getComment(), r.isHidden(), r.getCreatedAt(), r.getUpdatedAt());
        }).toList();
        return new PageResult<>(rows, page, size, result.getTotalElements());
    }

    @Transactional
    public void setHidden(UUID reviewId, boolean hidden) {
        ProductReview review = reviews.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review " + reviewId + " not found"));
        if (review.isHidden() != hidden) {
            review.setHidden(hidden);
            events.publishEvent(new CatalogChangedEvent());
        }
    }

    private Product product(String productId) {
        return products.findById(productId).orElseThrow(() -> new NotFoundException("Product " + productId + " not found"));
    }

    private Map<UUID, User> usersOf(Collection<ProductReview> page) {
        return users.findAllById(page.stream().map(ProductReview::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static PublicReview toPublic(ProductReview r, User author) {
        return new PublicReview(r.getId(), displayName(author), author == null ? null : author.getPictureUrl(),
                r.getRating(), r.getComment(), r.getCreatedAt(), r.getUpdatedAt());
    }

    static String displayName(User author) {
        if (author == null || author.getName() == null || author.getName().isBlank()) {
            return "Customer";
        }
        String[] parts = author.getName().strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }
}

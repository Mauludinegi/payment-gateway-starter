package io.github.mauludinegi.payments.review;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One per customer and product; editing replaces it. Hidden reviews stay stored but leave the store and its averages. */
@Entity
@Table(name = "product_reviews")
public class ProductReview {

    @Id
    private UUID id;
    private String productId;
    private UUID userId;
    private short rating;
    private String comment;
    private boolean hidden;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProductReview() {
    }

    public ProductReview(String productId, UUID userId, int rating, String comment, Instant now) {
        this.id = UUID.randomUUID();
        this.productId = productId;
        this.userId = userId;
        this.createdAt = now;
        edit(rating, comment, now);
    }

    public void edit(int rating, String comment, Instant now) {
        this.rating = (short) rating;
        this.comment = comment;
        this.updatedAt = now;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public UUID getId() { return id; }
    public String getProductId() { return productId; }
    public UUID getUserId() { return userId; }
    public int getRating() { return rating; }
    public String getComment() { return comment; }
    public boolean isHidden() { return hidden; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

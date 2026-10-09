package io.github.mauludinegi.payments.review;

/** Average and count of a product's visible reviews. */
public record RatingSummary(String productId, double average, long count) {
}

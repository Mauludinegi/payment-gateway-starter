package io.github.mauludinegi.payments.review;

import io.github.mauludinegi.payments.admin.AdminService.PageResult;
import io.github.mauludinegi.payments.auth.UserAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReviewController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    public record ReviewForm(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {
    }

    public record Visibility(@NotNull Boolean hidden) {
    }

    @GetMapping("/api/products/{productId}/reviews")
    public PageResult<ReviewService.PublicReview> reviews(@PathVariable String productId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "10") int size) {
        return reviews.visible(productId, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/me/reviews/{productId}")
    public ReviewService.MyReview mine(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable String productId) {
        return reviews.mine(userId, productId);
    }

    @PutMapping("/api/me/reviews/{productId}")
    public ReviewService.PublicReview save(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable String productId,
                                           @Valid @RequestBody ReviewForm form) {
        return reviews.save(userId, productId, form.rating(), form.comment());
    }

    @DeleteMapping("/api/me/reviews/{productId}")
    public ResponseEntity<Void> delete(@RequestAttribute(UserAuth.USER_ID) UUID userId, @PathVariable String productId) {
        reviews.delete(userId, productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/reviews")
    public PageResult<ReviewService.AdminReview> forAdmin(@RequestParam(required = false) String productId,
                                                          @RequestParam(required = false) Boolean hidden,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return reviews.forAdmin(productId, hidden, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @PatchMapping("/api/admin/reviews/{id}")
    public ResponseEntity<Void> setHidden(@PathVariable UUID id, @Valid @RequestBody Visibility body) {
        reviews.setHidden(id, body.hidden());
        return ResponseEntity.noContent().build();
    }
}

package io.github.mauludinegi.payments.catalog;

import io.github.mauludinegi.payments.redis.InMemoryCache;
import io.github.mauludinegi.payments.redis.KeyValueCache;
import io.github.mauludinegi.payments.review.ProductReviewRepository;
import io.github.mauludinegi.payments.review.RatingSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductReviewRepository reviews = mock(ProductReviewRepository.class);
    private final ImageStore images = mock(ImageStore.class);

    @BeforeEach
    void setUp() {
        Product ebook = new Product("ebook-api", new Product.Details("Clean API Design", "E-book", "E-book",
                "i-lucide-book-open", 79_000, true, 1, null), NOW);
        Product review = new Product("review-1h", new Product.Details("1-Hour Code Review", "Review", "Service",
                "i-lucide-messages-square", 350_000, true, 2, 5), NOW);
        when(products.findByActiveTrueOrderBySortOrder()).thenReturn(List.of(ebook, review));
        when(reviews.summaries()).thenReturn(List.of(new RatingSummary("ebook-api", 4.666, 3)));
    }

    @Test
    void servesFromTheCacheUntilTheCatalogueChanges() {
        CatalogService catalog = new CatalogService(products, reviews, images, new InMemoryCache(Clock.systemUTC()),
                JsonMapper.builder().build());

        List<CatalogService.CatalogProduct> first = catalog.products();
        List<CatalogService.CatalogProduct> second = catalog.products();
        verify(products, times(1)).findByActiveTrueOrderBySortOrder();
        assertThat(second).isEqualTo(first);
        assertThat(first.get(0).rating()).isEqualTo(4.7);
        assertThat(first.get(0).reviewCount()).isEqualTo(3);
        assertThat(first.get(1).stock()).isEqualTo(5);
        assertThat(first.get(1).rating()).isZero();

        catalog.onCatalogChanged(new CatalogChangedEvent());
        catalog.products();
        verify(products, times(2)).findByActiveTrueOrderBySortOrder();
    }

    @Test
    void readsTheDatabaseWhenRedisIsDown() {
        KeyValueCache down = new KeyValueCache() {
            @Override
            public Optional<String> get(String key) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public void put(String key, String value, Duration ttl) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public void delete(String key) {
                throw new IllegalStateException("connection refused");
            }
        };
        CatalogService catalog = new CatalogService(products, reviews, images, down, JsonMapper.builder().build());

        assertThat(catalog.products()).extracting(CatalogService.CatalogProduct::id).containsExactly("ebook-api", "review-1h");
        catalog.onCatalogChanged(new CatalogChangedEvent());
    }
}

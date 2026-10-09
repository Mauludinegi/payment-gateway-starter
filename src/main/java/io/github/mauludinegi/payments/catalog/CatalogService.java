package io.github.mauludinegi.payments.catalog;

import io.github.mauludinegi.payments.redis.KeyValueCache;
import io.github.mauludinegi.payments.review.ProductReviewRepository;
import io.github.mauludinegi.payments.review.RatingSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The store's product list, cached in Redis and dropped whenever a product, its stock, or its reviews change.
 * Only for display: checkout re-reads prices and takes stock in the database, so a stale cache can never sell
 * something at the wrong price or beyond its stock. If Redis is down, the store reads the database instead.
 */
@Service
public class CatalogService {

    static final String KEY = "catalog:products:v1";
    /** Also bounds how long a rare race (a read finishing after an eviction) can leave stale data behind. */
    static final Duration TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final TypeReference<List<CatalogProduct>> LIST = new TypeReference<>() {
    };

    private final ProductRepository products;
    private final ProductReviewRepository reviews;
    private final ImageStore images;
    private final KeyValueCache cache;
    private final JsonMapper json;

    public CatalogService(ProductRepository products, ProductReviewRepository reviews, ImageStore images,
                          KeyValueCache cache, JsonMapper json) {
        this.products = products;
        this.reviews = reviews;
        this.images = images;
        this.cache = cache;
        this.json = json;
    }

    /** {@code stock} null means unlimited; {@code rating} is 0 when there are no reviews. */
    public record CatalogProduct(String id, String name, String description, String category, String icon, long price,
                                 Integer stock, String imageUrl, double rating, long reviewCount) {
    }

    public List<CatalogProduct> products() {
        try {
            Optional<String> cached = cache.get(KEY);
            if (cached.isPresent()) {
                return json.readValue(cached.get(), LIST);
            }
        } catch (RuntimeException e) {
            log.warn("Catalogue cache read failed, using the database: {}", e.getMessage());
        }
        List<CatalogProduct> fresh = load();
        try {
            cache.put(KEY, json.writeValueAsString(fresh), TTL);
        } catch (RuntimeException e) {
            log.warn("Catalogue cache write failed: {}", e.getMessage());
        }
        return fresh;
    }

    public Optional<CatalogProduct> product(String id) {
        return products().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** After commit, so a reader cannot cache the old data again before the change is visible. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCatalogChanged(CatalogChangedEvent event) {
        try {
            cache.delete(KEY);
        } catch (RuntimeException e) {
            log.warn("Could not drop the catalogue cache; it expires within {}: {}", TTL, e.getMessage());
        }
    }

    private List<CatalogProduct> load() {
        Map<String, RatingSummary> ratings = reviews.summaries().stream()
                .collect(Collectors.toMap(RatingSummary::productId, Function.identity()));
        return products.findByActiveTrueOrderBySortOrder().stream().map(p -> {
            RatingSummary r = ratings.get(p.getId());
            return new CatalogProduct(p.getId(), p.getName(), p.getDescription(), p.getCategory(), p.getIcon(),
                    p.getPrice(), p.getStock(), p.getImageKey() == null ? null : images.url(p.getImageKey()),
                    r == null ? 0 : Math.round(r.average() * 10) / 10.0, r == null ? 0 : r.count());
        }).toList();
    }
}

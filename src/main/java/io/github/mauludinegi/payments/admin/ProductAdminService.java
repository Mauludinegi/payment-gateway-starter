package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.catalog.CatalogChangedEvent;
import io.github.mauludinegi.payments.catalog.ImageStore;
import io.github.mauludinegi.payments.catalog.ImageType;
import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The catalogue as admins manage it. Products are never deleted, because placed orders point to them; deactivating
 * one takes it out of the store.
 */
@Service
public class ProductAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);

    private final ProductRepository products;
    private final ImageStore images;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProductAdminService(ProductRepository products, ImageStore images, PlatformTransactionManager transactions,
                               ApplicationEventPublisher events, Clock clock) {
        this.products = products;
        this.images = images;
        this.tx = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Product> products() {
        return products.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional
    public Product create(String id, Product.Details details) {
        if (products.existsById(id)) {
            throw new IllegalStateException("A product with the ID " + id + " already exists");
        }
        events.publishEvent(new CatalogChangedEvent());
        return products.save(new Product(id, details, clock.instant()));
    }

    @Transactional
    public Product update(String id, Product.Details details, long expectedVersion) {
        Product product = find(id);
        if (product.getVersion() != expectedVersion) {
            throw new IllegalStateException(product.getName() + " changed since you opened it (stock may have been "
                    + "sold). Reload it and save again.");
        }
        product.update(details, clock.instant());
        events.publishEvent(new CatalogChangedEvent());
        return product;
    }

    /**
     * Uploads first and only then points the product at the new image, so the store never shows a missing one.
     * Each upload gets a new key, which lets browsers and the CDN cache images forever.
     */
    public Product changeImage(String id, byte[] bytes) {
        if (bytes.length > ImageType.MAX_BYTES) {
            throw new IllegalArgumentException("The image is larger than 2 MB");
        }
        ImageType type = ImageType.detect(bytes)
                .orElseThrow(() -> new IllegalArgumentException("Upload a JPG, PNG, or WebP image"));
        find(id);
        String key = "products/" + UUID.randomUUID() + "." + type.extension();
        images.put(key, bytes, type);
        Swap swap;
        try {
            swap = swapImage(id, key);
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        deleteQuietly(swap.previousKey());
        return swap.product();
    }

    public Product removeImage(String id) {
        Swap swap = swapImage(id, null);
        deleteQuietly(swap.previousKey());
        return swap.product();
    }

    private Swap swapImage(String id, String key) {
        return tx.execute(status -> {
            Product product = find(id);
            String previous = product.getImageKey();
            product.changeImage(key, clock.instant());
            events.publishEvent(new CatalogChangedEvent());
            return new Swap(product, previous);
        });
    }

    private record Swap(Product product, String previousKey) {
    }

    private Product find(String id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }

    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            images.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete image {}: {}", key, e.getMessage());
        }
    }
}

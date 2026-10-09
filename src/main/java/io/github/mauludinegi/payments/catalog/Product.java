package io.github.mauludinegi.payments.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private String id;
    private String name;
    private String description;
    private String category;
    private String icon;
    private long price;
    private boolean active;
    private int sortOrder;
    private String imageKey;
    private Instant updatedAt;
    /** Null means unlimited. Orders change it with {@link ProductRepository#takeStock} and {@code returnStock}. */
    private Integer stock;

    @Version
    private long version;

    protected Product() {
    }

    public Product(String id, Details details, Instant now) {
        this.id = id;
        update(details, now);
    }

    /** Placed orders keep their own copy of name and price, so editing a product never changes them. */
    public void update(Details details, Instant now) {
        this.name = details.name();
        this.description = details.description();
        this.category = details.category();
        this.icon = details.icon();
        this.price = details.price();
        this.active = details.active();
        this.sortOrder = details.sortOrder();
        this.stock = details.stock();
        this.updatedAt = now;
    }

    public void changeImage(String imageKey, Instant now) {
        this.imageKey = imageKey;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getIcon() { return icon; }
    public long getPrice() { return price; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
    public String getImageKey() { return imageKey; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getStock() { return stock; }
    public long getVersion() { return version; }

    public record Details(String name, String description, String category, String icon, long price, boolean active,
                          int sortOrder, Integer stock) {
    }
}

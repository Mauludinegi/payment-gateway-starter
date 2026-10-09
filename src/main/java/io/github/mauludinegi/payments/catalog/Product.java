package io.github.mauludinegi.payments.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    protected Product() {
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getIcon() { return icon; }
    public long getPrice() { return price; }
    public boolean isActive() { return active; }
}

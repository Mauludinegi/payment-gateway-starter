package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.catalog.ImageStore;
import io.github.mauludinegi.payments.catalog.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/products")
public class ProductAdminController {

    private final ProductAdminService admin;
    private final ImageStore images;

    public ProductAdminController(ProductAdminService admin, ImageStore images) {
        this.admin = admin;
        this.images = images;
    }

    /**
     * {@code id} is only read when creating; it becomes part of order history and cannot change afterwards.
     * {@code version} is required when editing: orders change stock all the time, and saving a form opened earlier
     * must not put back stock that was sold in the meantime. Empty {@code stock} means unlimited.
     */
    public record ProductForm(
            @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "use lowercase letters, digits, and dashes") @Size(max = 40) String id,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 500) String description,
            @NotBlank @Size(max = 40) String category,
            @NotBlank @Pattern(regexp = "i-[a-z0-9]+(-[a-z0-9]+)+", message = "use an icon name such as i-lucide-book-open") @Size(max = 60) String icon,
            @Min(1) @Max(100_000_000) long price,
            boolean active,
            @Min(0) @Max(9999) int sortOrder,
            @Min(0) @Max(1_000_000) Integer stock,
            Long version) {

        Product.Details details() {
            return new Product.Details(name.strip(), description.strip(), category.strip(), icon, price, active, sortOrder, stock);
        }
    }

    public record AdminProduct(String id, String name, String description, String category, String icon, long price,
                               boolean active, int sortOrder, Integer stock, String imageUrl, Instant updatedAt,
                               long version) {
    }

    @GetMapping
    public List<AdminProduct> products() {
        return admin.products().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProduct create(@Valid @RequestBody ProductForm form) {
        if (form.id() == null || form.id().isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return view(admin.create(form.id(), form.details()));
    }

    @PutMapping("/{id}")
    public AdminProduct update(@PathVariable String id, @Valid @RequestBody ProductForm form) {
        if (form.version() == null) {
            throw new IllegalArgumentException("version is required");
        }
        return view(admin.update(id, form.details(), form.version()));
    }

    @PutMapping(path = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminProduct changeImage(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        return view(admin.changeImage(id, file.getBytes()));
    }

    @DeleteMapping("/{id}/image")
    public AdminProduct removeImage(@PathVariable String id) {
        return view(admin.removeImage(id));
    }

    private AdminProduct view(Product p) {
        return new AdminProduct(p.getId(), p.getName(), p.getDescription(), p.getCategory(), p.getIcon(), p.getPrice(),
                p.isActive(), p.getSortOrder(), p.getStock(), p.getImageKey() == null ? null : images.url(p.getImageKey()),
                p.getUpdatedAt(), p.getVersion());
    }
}

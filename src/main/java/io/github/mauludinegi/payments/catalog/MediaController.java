package io.github.mauludinegi.payments.catalog;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Serves images from {@link LocalImageStore}. With Supabase Storage, browsers load them from Supabase instead. */
@RestController
public class MediaController {

    private final ImageStore images;

    public MediaController(ImageStore images) {
        this.images = images;
    }

    @GetMapping("/api/media/products/{name:[a-z0-9-]+}.{extension:jpg|png|webp}")
    public ResponseEntity<Resource> productImage(@PathVariable String name, @PathVariable String extension) {
        if (!(images instanceof LocalImageStore local)) {
            return ResponseEntity.notFound().build();
        }
        ImageType type = ImageType.fromExtension(extension).orElseThrow();
        return local.file("products/" + name + "." + extension)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(type.mimeType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .<Resource>body(new FileSystemResource(file)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

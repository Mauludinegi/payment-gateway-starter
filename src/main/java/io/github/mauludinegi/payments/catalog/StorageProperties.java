package io.github.mauludinegi.payments.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** Where product images go: a public Supabase Storage bucket, or a local folder when Supabase is not set up. */
@ConfigurationProperties("storage")
public record StorageProperties(@DefaultValue Supabase supabase, @DefaultValue("data/media") Path localDir) {

    public record Supabase(String url, String secretKey, @DefaultValue("product-images") String bucket) {

        public boolean configured() {
            return url != null && !url.isBlank() && secretKey != null && !secretKey.isBlank();
        }
    }
}

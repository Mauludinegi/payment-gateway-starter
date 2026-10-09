package io.github.mauludinegi.payments.catalog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Images in a public Supabase Storage bucket, served from Supabase's CDN. The bucket is created on the first upload
 * if it does not exist, limited to the accepted image types.
 */
public class SupabaseImageStore implements ImageStore {

    private final RestClient http;
    private final String publicBase;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    public SupabaseImageStore(RestClient.Builder builder, StorageProperties.Supabase supabase) {
        String base = supabase.url().replaceAll("/+$", "") + "/storage/v1";
        this.publicBase = base + "/object/public/" + supabase.bucket() + "/";
        this.bucket = supabase.bucket();
        RestClient.Builder client = builder.clone().baseUrl(base).defaultHeader("apikey", supabase.secretKey());
        // Legacy service_role keys are JWTs and also go in Authorization; the newer sb_secret_ keys only in apikey.
        if (supabase.secretKey().startsWith("eyJ")) {
            client.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + supabase.secretKey());
        }
        this.http = client.build();
    }

    @Override
    public void put(String key, byte[] bytes, ImageType type) {
        ensureBucket();
        try {
            http.post()
                    .uri(objectPath(key))
                    .contentType(MediaType.parseMediaType(type.mimeType()))
                    // Keys are never reused, so browsers and the CDN may keep a copy for a year.
                    .header(HttpHeaders.CACHE_CONTROL, "31536000")
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new StorageException("Supabase Storage rejected the upload: " + detail(e), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            http.delete().uri(objectPath(key)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new StorageException("Supabase Storage could not delete " + key + ": " + detail(e), e);
        }
    }

    @Override
    public String url(String key) {
        return publicBase + key;
    }

    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        try {
            http.post()
                    .uri("/bucket")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "id", bucket,
                            "name", bucket,
                            "public", true,
                            "file_size_limit", ImageType.MAX_BYTES,
                            "allowed_mime_types", List.of(ImageType.JPEG.mimeType(), ImageType.PNG.mimeType(), ImageType.WEBP.mimeType())))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (!alreadyExists(e)) {
                throw new StorageException("Could not create the Supabase Storage bucket " + bucket + ": " + detail(e), e);
            }
        } catch (RestClientException e) {
            throw new StorageException("Supabase Storage is not reachable: " + e.getMessage(), e);
        }
        bucketReady.set(true);
    }

    /** Keys are generated here ({@code products/<uuid>.<ext>}); their slash must reach Supabase unencoded. */
    private String objectPath(String key) {
        return "/object/" + bucket + "/" + key;
    }

    private static boolean alreadyExists(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT) || body.contains("already exists") || body.contains("Duplicate");
    }

    private static String detail(RestClientException e) {
        return e instanceof RestClientResponseException r ? r.getStatusCode().value() + " " + r.getResponseBodyAsString() : e.getMessage();
    }
}

package io.github.mauludinegi.payments.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseImageStoreTest {

    private static final String BASE = "https://abc.supabase.co/storage/v1";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

    @Test
    void createsThePublicBucketOnceThenUploads() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseImageStore store = new SupabaseImageStore(builder,
                new StorageProperties.Supabase("https://abc.supabase.co/", "sb_secret_test", "product-images"));

        server.expect(requestTo(BASE + "/bucket"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "sb_secret_test"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("{\"id\":\"product-images\",\"public\":true,\"file_size_limit\":2097152}"))
                .andRespond(withSuccess("{\"name\":\"product-images\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/object/product-images/products/a.png"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/object/product-images/products/b.png"))
                .andRespond(withSuccess());

        store.put("products/a.png", PNG, ImageType.PNG);
        store.put("products/b.png", PNG, ImageType.PNG);

        server.verify();
        assertThat(store.url("products/a.png")).isEqualTo(BASE + "/object/public/product-images/products/a.png");
    }

    @Test
    void anExistingBucketIsFineAndLegacyKeysAlsoGoInAuthorization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseImageStore store = new SupabaseImageStore(builder,
                new StorageProperties.Supabase("https://abc.supabase.co", "eyJlegacy", "product-images"));

        server.expect(requestTo(BASE + "/bucket"))
                .andExpect(header("Authorization", "Bearer eyJlegacy"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"409\",\"error\":\"Duplicate\",\"message\":\"The resource already exists\"}"));
        server.expect(requestTo(BASE + "/object/product-images/products/a.png")).andRespond(withSuccess());

        store.put("products/a.png", PNG, ImageType.PNG);
        server.verify();
    }

    @Test
    void surfacesStorageErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseImageStore store = new SupabaseImageStore(builder,
                new StorageProperties.Supabase("https://abc.supabase.co", "sb_secret_wrong", "product-images"));

        server.expect(requestTo(BASE + "/bucket"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"message\":\"Invalid API key\"}"));

        assertThatThrownBy(() -> store.put("products/a.png", PNG, ImageType.PNG))
                .isInstanceOf(ImageStore.StorageException.class)
                .hasMessageContaining("Invalid API key");
    }
}

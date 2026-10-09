package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admins manage the catalogue and its images; images are checked by content, not by name or content type. */
@SpringBootTest(properties = "storage.local-dir=${java.io.tmpdir}/payments-test-media")
class ProductAdminApiTest {

    private static final byte[] PNG = withHeader(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    private static final byte[] JPEG = withHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    AuthService auth;

    MockMvc mvc;
    String admin;
    String customer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        admin = "Bearer " + auth.signInForDevelopment("Ana Admin", "admin@example.com").session().token();
        customer = "Bearer " + auth.signInForDevelopment("Budi", "budi.products@example.com").session().token();
    }

    @Test
    void onlyAdminsManageProducts() throws Exception {
        mvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/products").header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON).content(form("hacked", 1000, true)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void createsEditsAndDeactivatesProducts() throws Exception {
        String created = mvc.perform(post("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form("ebook-go", 59000, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ebook-go"))
                .andExpect(jsonPath("$.stock").value(nullValue()))
                .andExpect(jsonPath("$.imageUrl").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        long version = json.readTree(created).path("version").asLong();
        mvc.perform(post("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form("ebook-go", 59000, true)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form("Bad ID", 59000, true)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$[*].id", hasItem("ebook-go")));

        mvc.perform(put("/api/admin/products/{id}", "ebook-go").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form(null, 69000, true, 25, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(69000))
                .andExpect(jsonPath("$.stock").value(25));
        // A form opened before that save is refused instead of overwriting it (and any stock sold since).
        mvc.perform(put("/api/admin/products/{id}", "ebook-go").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form(null, 59000, true, 30, version)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/products/{id}", "ebook-go").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form(null, 69000, false, 25, version + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/products")).andExpect(jsonPath("$[*].id", not(hasItem("ebook-go"))));
        mvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[*].id", hasItem("ebook-go")));
        mvc.perform(put("/api/admin/products/{id}", "missing").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form(null, 1000, true, null, 0L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadsReplacesAndRemovesImages() throws Exception {
        mvc.perform(post("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(form("course-go", 149000, true)))
                .andExpect(status().isCreated());

        String first = uploadImage("course-go", "cover.png", PNG);
        assertThat(first).matches("/api/media/products/[a-z0-9-]+\\.png");
        mvc.perform(get(first))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/products")).andExpect(jsonPath("$[?(@.id == 'course-go')].imageUrl", hasItem(first)));

        String second = uploadImage("course-go", "cover.png", JPEG);
        assertThat(second).endsWith(".jpg");
        mvc.perform(get(first)).andExpect(status().isNotFound());

        // A script renamed to .png, sent as image/png, is still refused.
        mvc.perform(multipart(HttpMethod.PUT, "/api/admin/products/{id}/image", "course-go")
                        .file(new MockMultipartFile("file", "x.png", "image/png", "<svg onload=alert(1)>".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/admin/products/{id}/image", "course-go").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(nullValue()));
        mvc.perform(get(second)).andExpect(status().isNotFound());
    }

    private String uploadImage(String productId, String filename, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart(HttpMethod.PUT, "/api/admin/products/{id}/image", productId)
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl", startsWith("/api/media/products/")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("imageUrl").asString();
    }

    private String form(String id, long price, boolean active) {
        return form(id, price, active, null, null);
    }

    private String form(String id, long price, boolean active, Integer stock, Long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("name", "Go for Backend Developers");
        body.put("description", "Build and test HTTP services in Go.");
        body.put("category", "Course");
        body.put("icon", "i-lucide-book-open");
        body.put("price", price);
        body.put("active", active);
        body.put("sortOrder", 10);
        body.put("stock", stock);
        body.put("version", version);
        return json.writeValueAsString(body);
    }

    private static byte[] withHeader(byte[] header) {
        byte[] bytes = Arrays.copyOf(header, header.length + 32);
        Arrays.fill(bytes, header.length, bytes.length, (byte) 7);
        return bytes;
    }
}

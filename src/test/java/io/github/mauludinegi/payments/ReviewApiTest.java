package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.admin.ProductAdminService;
import io.github.mauludinegi.payments.auth.AuthService;
import io.github.mauludinegi.payments.catalog.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only paying customers review, once each; ratings in the store follow edits, deletions, and moderation. */
@SpringBootTest
class ReviewApiTest {

    private static final String PRODUCT = "review-target";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    ProductAdminService productAdmin;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        try {
            productAdmin.create(PRODUCT, new Product.Details("Reviewed Course", "A course to review.", "Course",
                    "i-lucide-star", 25_000, true, 60, null));
        } catch (IllegalStateException alreadyCreated) {
            // Shared context: the product exists from an earlier test.
        }
    }

    @Test
    void onlyBuyersWithAPaidOrderCanReview() throws Exception {
        String window = session("Window Shopper", "window.review@example.com");
        mvc.perform(get("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, window))
                .andExpect(jsonPath("$.canReview").value(false));
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, window)
                        .contentType(MediaType.APPLICATION_JSON).content(review(5, "Great")))
                .andExpect(status().isForbidden());

        // An unpaid order is not enough.
        String unpaid = session("Unpaid Buyer", "unpaid.review@example.com");
        createOrder(unpaid);
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, unpaid)
                        .contentType(MediaType.APPLICATION_JSON).content(review(5, "Great")))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).contentType(MediaType.APPLICATION_JSON).content(review(5, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ratingsFollowReviewsEditsAndModeration() throws Exception {
        String budi = paidBuyer("Budi Santoso", "budi.review@example.com");
        String sari = paidBuyer("Sari", "sari.review@example.com");
        String admin = session("Ana Admin", "admin@example.com");

        mvc.perform(get("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, budi))
                .andExpect(jsonPath("$.canReview").value(true));
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, budi)
                        .contentType(MediaType.APPLICATION_JSON).content(review(6, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, budi)
                        .contentType(MediaType.APPLICATION_JSON).content(review(4, "  Clear and practical.  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author").value("Budi S."))
                .andExpect(jsonPath("$.comment").value("Clear and practical."));
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, sari)
                        .contentType(MediaType.APPLICATION_JSON).content(review(1, "Too short")))
                .andExpect(status().isOk());
        rating(2.5, 2);

        // Editing replaces the review instead of adding one.
        mvc.perform(put("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, budi)
                        .contentType(MediaType.APPLICATION_JSON).content(review(5, "Even better the second time.")))
                .andExpect(status().isOk());
        rating(3.0, 2);
        mvc.perform(get("/api/products/{id}/reviews", PRODUCT))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(content().string(not(containsString("budi.review@example.com"))));

        mvc.perform(get("/api/admin/reviews").param("productId", PRODUCT).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].authorEmail").exists());
        String sariId = json.readTree(mvc.perform(get("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, sari))
                .andReturn().getResponse().getContentAsString()).path("review").path("id").asString();
        mvc.perform(patch("/api/admin/reviews/{id}", sariId).header(HttpHeaders.AUTHORIZATION, budi)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/reviews/{id}", sariId).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isNoContent());
        rating(5.0, 1);
        mvc.perform(get("/api/products/{id}/reviews", PRODUCT)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, sari))
                .andExpect(jsonPath("$.hidden").value(true));

        mvc.perform(delete("/api/me/reviews/{id}", PRODUCT).header(HttpHeaders.AUTHORIZATION, budi))
                .andExpect(status().isNoContent());
        rating(0.0, 0);
    }

    private void rating(double average, int count) throws Exception {
        mvc.perform(get("/api/products/{id}", PRODUCT))
                .andExpect(jsonPath("$.rating").value(average))
                .andExpect(jsonPath("$.reviewCount").value(count));
    }

    private String paidBuyer(String name, String email) throws Exception {
        String session = session(name, email);
        String orderId = createOrder(session);
        mvc.perform(post("/api/orders/{id}/payments", orderId).header(HttpHeaders.AUTHORIZATION, session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BRI_VA\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId)).andExpect(status().isOk());
        return session;
    }

    private String createOrder(String session) throws Exception {
        String body = mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asString();
    }

    private String session(String name, String email) {
        return "Bearer " + auth.signInForDevelopment(name, email).session().token();
    }

    private static String review(int rating, String comment) {
        return comment == null ? "{\"rating\":" + rating + "}" : "{\"rating\":" + rating + ",\"comment\":\"" + comment + "\"}";
    }
}

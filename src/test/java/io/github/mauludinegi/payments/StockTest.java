package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.admin.ProductAdminService;
import io.github.mauludinegi.payments.auth.AuthService;
import io.github.mauludinegi.payments.catalog.Product;
import io.github.mauludinegi.payments.catalog.ProductRepository;
import io.github.mauludinegi.payments.service.CheckoutService;
import io.github.mauludinegi.payments.service.PaymentStatusService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limited stock is held from checkout, given back when an order expires, and never oversold. */
@SpringBootTest
class StockTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    ProductAdminService productAdmin;
    @Autowired
    ProductRepository products;
    @Autowired
    PaymentStatusService statuses;
    @Autowired
    CheckoutService checkout;

    MockMvc mvc;
    String admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        admin = "Bearer " + auth.signInForDevelopment("Ana Admin", "admin@example.com").session().token();
    }

    @Test
    void checkoutHoldsStockAndExpiryGivesItBack() throws Exception {
        limitedProduct("stock-hold", 2);
        String ani = customer("ani");

        UUID order = createOrder(ani, "stock-hold", 2);
        assertThat(stockOf("stock-hold")).isZero();
        mvc.perform(get("/api/products/{id}", "stock-hold")).andExpect(jsonPath("$.stock").value(0));

        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, customer("bayu"))
                        .contentType(MediaType.APPLICATION_JSON).content(cart("stock-hold", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("sold out")));

        statuses.expireOrder(order);
        statuses.expireOrder(order);
        assertThat(stockOf("stock-hold")).isEqualTo(2);
        mvc.perform(get("/api/products/{id}", "stock-hold")).andExpect(jsonPath("$.stock").value(2));
    }

    @Test
    void aLatePaymentTakesStockAgainOrIsFlaggedForTheAdmin() throws Exception {
        limitedProduct("stock-late", 1);
        String citra = customer("citra");
        UUID late = createOrder(citra, "stock-late", 1);
        startPayment(citra, late);
        statuses.expireOrder(late);
        assertThat(stockOf("stock-late")).isEqualTo(1);

        // Someone else buys the unit Citra's expired order gave back...
        String dodi = customer("dodi");
        UUID other = createOrder(dodi, "stock-late", 1);
        startPayment(dodi, other);
        pay(other);

        // ...then Citra's old VA is paid after all: the order is paid, never oversold, and flagged.
        pay(late);
        assertThat(stockOf("stock-late")).isZero();
        mvc.perform(get("/api/admin/orders/{id}", late).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.stockShort").value(true));
        mvc.perform(get("/api/admin/orders/{id}", other).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.stockShort").value(false));

        // With stock still there, a late payment simply takes it again.
        limitedProduct("stock-late-ok", 3);
        UUID again = createOrder(citra, "stock-late-ok", 1);
        startPayment(citra, again);
        statuses.expireOrder(again);
        pay(again);
        assertThat(stockOf("stock-late-ok")).isEqualTo(2);
        mvc.perform(get("/api/admin/orders/{id}", again).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.stockShort").value(false));
    }

    @Test
    void concurrentCheckoutsNeverSellMoreThanTheStock() throws Exception {
        limitedProduct("stock-race", 3);
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            buyers.add(auth.signInForDevelopment("Racer " + i, "racer" + i + "@example.com").user().getId());
        }

        List<Callable<Boolean>> attempts = buyers.stream().<Callable<Boolean>>map(buyer -> () -> {
            try {
                checkout.createOrder(buyer, List.of(new CheckoutService.CartLine("stock-race", 1)), "Racer", null);
                return true;
            } catch (IllegalStateException soldOut) {
                return false;
            }
        }).toList();
        int sold = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            for (Future<Boolean> result : pool.invokeAll(attempts)) {
                sold += result.get() ? 1 : 0;
            }
        }

        assertThat(sold).isEqualTo(3);
        assertThat(stockOf("stock-race")).isZero();
    }

    private void limitedProduct(String id, int stock) {
        productAdmin.create(id, new Product.Details("Limited " + id, "Only a few.", "Service", "i-lucide-ticket", 50_000,
                true, 50, stock));
    }

    private int stockOf(String id) {
        return products.findById(id).orElseThrow().getStock();
    }

    private String customer(String name) {
        return "Bearer " + auth.signInForDevelopment(name, name + ".stock@example.com").session().token();
    }

    private String cart(String productId, int quantity) {
        return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}]}";
    }

    private UUID createOrder(String session, String productId, int quantity) throws Exception {
        String body = mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, session)
                        .contentType(MediaType.APPLICATION_JSON).content(cart(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asString());
    }

    private void startPayment(String session, UUID orderId) throws Exception {
        mvc.perform(post("/api/orders/{id}/payments", orderId).header(HttpHeaders.AUTHORIZATION, session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BRI_VA\"}"))
                .andExpect(status().isCreated());
    }

    private void pay(UUID orderId) throws Exception {
        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId)).andExpect(status().isOk());
    }
}

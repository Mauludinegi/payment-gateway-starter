package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.gateway.simulator.SimulatorGateway;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "admin.token=test-admin-token")
class AdminApiTest {

    private static final String BEARER = "Bearer test-admin-token";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    SimulatorGateway simulator;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        mvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stats").header(HttpHeaders.AUTHORIZATION, "Bearer demo-admin-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/session").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk());
    }

    @Test
    void showsOrderPaymentsAndWebhooks() throws Exception {
        String orderId = createOrder("Siti Admin");
        pay(orderId, "BNI_VA");
        pay(orderId, "QRIS");
        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId)).andExpect(status().isOk());

        mvc.perform(get("/api/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.items[0].productId").value("course-k8s"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].channel").value("QRIS"))
                .andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.payments[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.webhooks[0].confirmedStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.webhooks[0].outcome").value("UPDATED"))
                .andExpect(jsonPath("$.needsRefund").value(false));

        mvc.perform(get("/api/admin/orders").param("q", "siti admin").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("QRIS"))
                .andExpect(jsonPath("$.items[0].paymentStatus").value("SUCCEEDED"));

        mvc.perform(get("/api/admin/orders").param("status", "PAID").param("q", "siti").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/orders").param("status", "EXPIRED").param("q", "siti").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/admin/webhooks").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").exists());

        mvc.perform(get("/api/admin/stats").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last7Days.length()").value(7))
                .andExpect(jsonPath("$.channels[?(@.channel == 'QRIS')]").exists());
    }

    @Test
    void flagsOrdersPaidTwiceForRefund() throws Exception {
        String orderId = createOrder("Double Payer");
        pay(orderId, "MANDIRI_VA");
        String oldRef = adminPayments(orderId).path(0).path("providerRef").asString();
        pay(orderId, "DANA");
        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId)).andExpect(status().isOk());

        SimulatorGateway.SignedWebhook late = simulator.simulate(oldRef, PaymentStatus.SUCCEEDED);
        mvc.perform(post("/webhooks/simulator").header(SimulatorGateway.SIGNATURE_HEADER, late.signature()).content(late.body()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(jsonPath("$.needsRefund").value(true));
    }

    @Test
    void syncAppliesTheGatewayStatusWhenAWebhookWasMissed() throws Exception {
        String orderId = createOrder("Missed Webhook");
        pay(orderId, "PERMATA_VA");
        JsonNode payment = adminPayments(orderId).path(0);
        // The gateway knows it was paid, but no webhook was delivered.
        simulator.simulate(payment.path("providerRef").asString(), PaymentStatus.SUCCEEDED);

        mvc.perform(post("/api/admin/payments/{id}/sync", payment.path("id").asString()).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.outcome").value("UPDATED"));
        mvc.perform(get("/api/orders/{id}", orderId)).andExpect(jsonPath("$.status").value("PAID"));
    }

    private String createOrder(String customer) throws Exception {
        String response = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"course-k8s\",\"quantity\":1}],\"customerName\":\"" + customer + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asString();
    }

    private void pay(String orderId, String channel) throws Exception {
        mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + channel + "\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode adminPayments(String orderId) throws Exception {
        String response = mvc.perform(get("/api/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("payments");
    }
}

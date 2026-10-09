package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.gateway.simulator.SimulatorGateway;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole checkout against the simulator gateway, through the real HTTP endpoints. */
@SpringBootTest
class CheckoutFlowTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    SimulatorGateway simulator;
    @Autowired
    PaymentAttemptRepository attempts;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void customerPaysAndOrderBecomesPaid() throws Exception {
        String orderId = createOrder();

        JsonNode payment = startPayment(orderId, "BRI_VA");
        assertThat(payment.path("payment").path("instructionType").asString()).isEqualTo("VIRTUAL_ACCOUNT_NUMBER");
        assertThat(payment.path("payment").path("instructionValue").asString()).startsWith("8808");

        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PROCESSED"));

        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));

        mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void redeliveredWebhookIsProcessedOnce() throws Exception {
        String orderId = createOrder();
        startPayment(orderId, "QRIS");
        String ref = latestRef(orderId);
        SimulatorGateway.SignedWebhook webhook = simulator.simulate(ref, PaymentStatus.SUCCEEDED);

        for (String expected : new String[]{"PROCESSED", "DUPLICATE", "DUPLICATE"}) {
            mvc.perform(post("/webhooks/simulator")
                            .header(SimulatorGateway.SIGNATURE_HEADER, webhook.signature())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(webhook.body()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value(expected));
        }
    }

    @Test
    void forgedWebhookIsRejected() throws Exception {
        String orderId = createOrder();
        startPayment(orderId, "DANA");
        String body = "{\"event_id\":\"x\",\"payment_ref\":\"" + latestRef(orderId) + "\",\"status\":\"SUCCEEDED\"}";

        mvc.perform(post("/webhooks/simulator").header(SimulatorGateway.SIGNATURE_HEADER, "forged").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void webhookPayloadIsReCheckedWithTheGateway() throws Exception {
        String orderId = createOrder();
        startPayment(orderId, "OVO");
        String ref = latestRef(orderId);
        // Correctly signed, but the gateway still reports the payment as pending.
        SimulatorGateway.SignedWebhook webhook = simulator.simulate(ref, PaymentStatus.SUCCEEDED);
        simulator.simulate(ref, PaymentStatus.PENDING);

        mvc.perform(post("/webhooks/simulator").header(SimulatorGateway.SIGNATURE_HEADER, webhook.signature()).content(webhook.body()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void changingMethodCancelsThePreviousPaymentButALatePaymentStillCounts() throws Exception {
        String orderId = createOrder();
        startPayment(orderId, "BCA_VA");
        String oldRef = latestRef(orderId);
        UUID oldAttempt = UUID.fromString(oldRef.substring(4));

        startPayment(orderId, "INDOMARET");
        assertThat(attempts.findById(oldAttempt).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        SimulatorGateway.SignedWebhook late = simulator.simulate(oldRef, PaymentStatus.SUCCEEDED);
        mvc.perform(post("/webhooks/simulator").header(SimulatorGateway.SIGNATURE_HEADER, late.signature()).content(late.body()))
                .andExpect(jsonPath("$.result").value("PROCESSED"));

        mvc.perform(get("/api/orders/{id}", orderId)).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void validatesInput() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"\",\"amount\":10,\"customerName\":\"Budi\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String createOrder() throws Exception {
        String response = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Premium plan\",\"amount\":150000,\"customerName\":\"Budi\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asString();
    }

    private JsonNode startPayment(String orderId, String channel) throws Exception {
        String body = "OVO".equals(channel)
                ? "{\"channel\":\"OVO\",\"mobileNumber\":\"+6281234567890\"}"
                : "{\"channel\":\"" + channel + "\"}";
        String response = mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String latestRef(String orderId) {
        return attempts.findLatestWithOrder(UUID.fromString(orderId)).orElseThrow().getProviderRef();
    }
}

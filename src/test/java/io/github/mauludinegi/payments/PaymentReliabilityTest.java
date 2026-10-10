package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.auth.AuthService;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.GatewayUnavailableException;
import io.github.mauludinegi.payments.gateway.simulator.SimulatorGateway;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Instruction;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.ExpiryJob;
import io.github.mauludinegi.payments.service.PaymentReconciler;
import io.github.mauludinegi.payments.webhook.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Double clicks, gateway timeouts, early webhooks and expiry, against the simulator gateway. */
@SpringBootTest
class PaymentReliabilityTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @MockitoSpyBean
    SimulatorGateway simulator;
    @Autowired
    PaymentAttemptRepository attempts;
    @Autowired
    OrderRepository orders;
    @Autowired
    WebhookEventRepository events;
    @Autowired
    PaymentReconciler reconciler;
    @Autowired
    ExpiryJob expiry;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthService auth;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(simulator);
        String session = auth.signInForDevelopment("Budi", "budi@example.com").session().token();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + session))
                .build();
    }

    @Test
    void concurrentRequestsWithTheSameKeyStartOnePayment() throws Exception {
        String orderId = createOrder();
        Callable<String> click = () -> json.readTree(pay(orderId, "BRI_VA", "click-1").getResponse().getContentAsString())
                .path("payment").path("id").asString();

        List<String> paymentIds;
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<String>> clicks = pool.invokeAll(List.of(click, click, click, click));
            paymentIds = clicks.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).distinct().toList();
        }

        assertThat(paymentIds).hasSize(1);
        assertThat(attempts.findByOrderIdInOrderByCreatedAtDesc(List.of(UUID.fromString(orderId)))).hasSize(1);
        verify(simulator, org.mockito.Mockito.times(1)).create(any());
    }

    @Test
    void reusingAKeyForAnotherMethodIsRejected() throws Exception {
        String orderId = createOrder();
        pay(orderId, "BRI_VA", "click-2");
        mvc.perform(post("/api/orders/{id}/payments", orderId).header("Idempotency-Key", "click-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/{id}/payments", orderId).header("Idempotency-Key", "not a key!")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeoutLeavesThePaymentConfirmingAndTheReconcilerRecoversIt() throws Exception {
        String orderId = createOrder();
        // The gateway makes the payment but the answer never arrives.
        doAnswer(call -> {
            call.callRealMethod();
            throw new GatewayUnavailableException("Read timed out");
        }).doCallRealMethod().when(simulator).create(any());

        mvc.perform(post("/api/orders/{id}/payments", orderId).header("Idempotency-Key", "click-3")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BCA_VA\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.confirming").value(true))
                .andExpect(jsonPath("$.payment.instructionValue").doesNotExist());

        // Another method cannot start while the first may still be payable.
        mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isConflict());

        reconciler.run(Instant.now().plusSeconds(1));

        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.payment.confirming").value(false))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.instructionType").value("VIRTUAL_ACCOUNT_NUMBER"));
        assertThat(attempts.findByOrderIdInOrderByCreatedAtDesc(List.of(UUID.fromString(orderId)))).hasSize(1);
    }

    @Test
    void clearRejectionStillFailsThePayment() throws Exception {
        String orderId = createOrder();
        doThrow(new GatewayException("Channel not activated")).when(simulator).create(any());

        mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BNI_VA\"}"))
                .andExpect(status().isBadGateway());
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.payment.status").value("FAILED"));
    }

    @Test
    void webhookForAPaymentWhoseCreateTimedOutStillPaysTheOrder() throws Exception {
        String orderId = createOrder();
        doAnswer(call -> {
            call.callRealMethod();
            throw new GatewayUnavailableException("Read timed out");
        }).when(simulator).create(any());
        mvc.perform(post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isAccepted());
        PaymentAttempt attempt = attempts.findLatestWithOrder(UUID.fromString(orderId)).orElseThrow();

        sendWebhook(simulator.simulate("sim-" + attempt.getId(), PaymentStatus.SUCCEEDED), "PROCESSED");

        mvc.perform(get("/api/orders/{id}", orderId)).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getProviderRef()).isEqualTo("sim-" + attempt.getId());
    }

    @Test
    void webhookForAnUnknownPaymentIsStoredAndReplayed() throws Exception {
        String orderId = createOrder();
        var order = orders.findById(UUID.fromString(orderId)).orElseThrow();
        Instant now = Instant.now();
        PaymentAttempt notYetSaved = new PaymentAttempt(order, Provider.SIMULATOR, Channel.QRIS, null, null, now, now.plus(Duration.ofMinutes(30)));
        String ref = "sim-" + notYetSaved.getId();
        SimulatorGateway.SignedWebhook webhook = simulator.simulate(ref, PaymentStatus.SUCCEEDED);

        sendWebhook(webhook, "QUEUED");
        sendWebhook(webhook, "DUPLICATE");
        assertThat(events.findTop100ByReplayPendingTrueOrderByReceivedAt()).anyMatch(e -> ref.equals(e.getProviderRef()));

        notYetSaved.attachGatewayPayment(ref, new Instruction(Instruction.Type.QR_STRING, "qr"), null, now);
        attempts.save(notYetSaved);
        reconciler.run();

        mvc.perform(get("/api/orders/{id}", orderId)).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(events.findTop100ByReplayPendingTrueOrderByReceivedAt()).noneMatch(e -> ref.equals(e.getProviderRef()));
    }

    @Test
    void expiryCancelsAtTheGatewayBeforeReleasingStock() throws Exception {
        String orderId = createOrder();
        pay(orderId, "MANDIRI_VA", null);
        String ref = attempts.findLatestWithOrder(UUID.fromString(orderId)).orElseThrow().getProviderRef();
        pastExpiry(orderId);

        // The gateway does not confirm the cancel: the payment could still be paid, so nothing is released.
        doNothing().when(simulator).cancel(anyString());
        expiry.run();
        verify(simulator).cancel(ref);
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"));

        reset(simulator);
        expiry.run();
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.payment.status").value("EXPIRED"));
    }

    private void pastExpiry(String orderId) {
        java.sql.Timestamp past = java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        jdbc.update("update payment_attempts set expires_at = ? where order_id = ?", past, UUID.fromString(orderId));
        jdbc.update("update orders set expires_at = ? where id = ?", past, UUID.fromString(orderId));
    }

    private void sendWebhook(SimulatorGateway.SignedWebhook webhook, String expected) throws Exception {
        mvc.perform(post("/webhooks/simulator")
                        .header(SimulatorGateway.SIGNATURE_HEADER, webhook.signature())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhook.body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(expected));
    }

    private MvcResult pay(String orderId, String channel, String key) throws Exception {
        var request = post("/api/orders/{id}/payments", orderId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private String createOrder() throws Exception {
        String response = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"course-k8s\",\"quantity\":1}],\"customerName\":\"Budi\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asString();
    }
}

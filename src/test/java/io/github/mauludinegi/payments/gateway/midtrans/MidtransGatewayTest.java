package io.github.mauludinegi.payments.gateway.midtrans;

import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.InvalidWebhookException;
import io.github.mauludinegi.payments.gateway.PaymentRequest;
import io.github.mauludinegi.payments.gateway.WebhookNotification;
import io.github.mauludinegi.payments.gateway.WebhookSecrets;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Instruction;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MidtransGatewayTest {

    private static final String SERVER_KEY = "SB-Mid-server-test";
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private MockRestServiceServer server;
    private MidtransGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentsProperties properties = new PaymentsProperties(null, null, null, null, null, null,
                new PaymentsProperties.Midtrans(SERVER_KEY, "https://api.midtrans.test"), null);
        gateway = new MidtransGateway(builder, properties, JsonMapper.builder().build());
    }

    @Test
    void chargesBankTransfer() {
        UUID attemptId = UUID.randomUUID();
        server.expect(requestTo("https://api.midtrans.test/v2/charge"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.payment_type").value("bank_transfer"))
                .andExpect(jsonPath("$.bank_transfer.bank").value("bca"))
                .andExpect(jsonPath("$.transaction_details.order_id").value(attemptId.toString()))
                .andExpect(jsonPath("$.transaction_details.gross_amount").value(150000))
                .andRespond(withSuccess("""
                        {"status_code":"201","transaction_status":"pending","order_id":"%s",
                         "va_numbers":[{"bank":"bca","va_number":"12345678901"}],"expiry_time":"2026-10-09 18:00:00"}
                        """.formatted(attemptId), MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.create(request(attemptId, Channel.BCA_VA));

        assertThat(payment.providerRef()).isEqualTo(attemptId.toString());
        assertThat(payment.instruction()).isEqualTo(new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, "12345678901"));
        assertThat(payment.expiresAt()).isEqualTo(Instant.parse("2026-10-09T11:00:00Z"));
    }

    @Test
    void chargesConvenienceStore() {
        server.expect(requestTo("https://api.midtrans.test/v2/charge"))
                .andExpect(jsonPath("$.payment_type").value("cstore"))
                .andExpect(jsonPath("$.cstore.store").value("alfamart"))
                .andRespond(withSuccess("{\"status_code\":\"201\",\"payment_code\":\"010811223344\"}", MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.create(request(UUID.randomUUID(), Channel.ALFAMART));

        assertThat(payment.instruction()).isEqualTo(new Instruction(Instruction.Type.PAYMENT_CODE, "010811223344"));
    }

    @Test
    void errorStatusInBodyIsAnError() {
        server.expect(requestTo("https://api.midtrans.test/v2/charge"))
                .andRespond(withSuccess("{\"status_code\":\"402\",\"status_message\":\"Payment channel is not activated.\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.create(request(UUID.randomUUID(), Channel.BRI_VA)))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("not activated");
    }

    @Test
    void verifiesNotificationSignature() {
        String orderId = UUID.randomUUID().toString();
        String signature = WebhookSecrets.sha512Hex(orderId + "200" + "150000.00" + SERVER_KEY);
        String body = """
                {"order_id":"%s","status_code":"200","gross_amount":"150000.00","signature_key":"%s",
                 "transaction_status":"settlement","transaction_id":"tx-1"}
                """.formatted(orderId, signature);

        WebhookNotification notification = gateway.parseWebhook(Map.of(), body);

        assertThat(notification.eventKey()).isEqualTo("tx-1:settlement");
        assertThat(notification.attemptId()).hasToString(orderId);
    }

    @Test
    void rejectsTamperedNotification() {
        String orderId = UUID.randomUUID().toString();
        String signature = WebhookSecrets.sha512Hex(orderId + "200" + "150000.00" + SERVER_KEY);
        String body = """
                {"order_id":"%s","status_code":"200","gross_amount":"1000.00","signature_key":"%s",
                 "transaction_status":"settlement","transaction_id":"tx-1"}
                """.formatted(orderId, signature);

        assertThatThrownBy(() -> gateway.parseWebhook(Map.of(), body)).isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void mapsTransactionStatus() {
        assertThat(MidtransGateway.mapStatus("settlement", "")).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(MidtransGateway.mapStatus("capture", "accept")).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(MidtransGateway.mapStatus("capture", "challenge")).isEqualTo(PaymentStatus.PENDING);
        assertThat(MidtransGateway.mapStatus("expire", "")).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(MidtransGateway.mapStatus("deny", "")).isEqualTo(PaymentStatus.FAILED);
        assertThat(MidtransGateway.mapStatus("pending", "")).isEqualTo(PaymentStatus.PENDING);
    }

    private static PaymentRequest request(UUID attemptId, Channel channel) {
        return new PaymentRequest(attemptId, ORDER_ID, "ORD-1", "Premium plan", 150_000, "Budi", null, channel,
                Instant.now().plus(1, ChronoUnit.HOURS));
    }
}

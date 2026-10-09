package io.github.mauludinegi.payments.gateway.xendit;

import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.InvalidWebhookException;
import io.github.mauludinegi.payments.gateway.PaymentRequest;
import io.github.mauludinegi.payments.gateway.WebhookNotification;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Instruction;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XenditGatewayTest {

    private static final String TOKEN = "callback-token-123";

    private MockRestServiceServer server;
    private XenditGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentsProperties properties = new PaymentsProperties(null, null, null, null, "https://shop.test/return",
                new PaymentsProperties.Xendit("xnd_development_key", TOKEN, "https://api.xendit.test"), null, null);
        gateway = new XenditGateway(builder, properties, JsonMapper.builder().build());
    }

    @Test
    void createsVirtualAccountWithV3Request() {
        UUID attemptId = UUID.randomUUID();
        server.expect(requestTo("https://api.xendit.test/v3/payment_requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-version", "2024-11-11"))
                .andExpect(header("Authorization", "Basic eG5kX2RldmVsb3BtZW50X2tleTo="))
                .andExpect(jsonPath("$.reference_id").value(attemptId.toString()))
                .andExpect(jsonPath("$.type").value("PAY"))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.request_amount").value(150000))
                .andExpect(jsonPath("$.channel_code").value("BRI_VIRTUAL_ACCOUNT"))
                .andExpect(jsonPath("$.channel_properties.display_name").value("Budi"))
                .andRespond(withSuccess("""
                        {"payment_request_id":"pr-1","status":"REQUIRES_ACTION",
                         "channel_properties":{"display_name":"Budi","expires_at":"2026-10-09T11:00:00Z"},
                         "actions":[{"type":"PRESENT_TO_CUSTOMER","descriptor":"VIRTUAL_ACCOUNT_NUMBER","value":"8808123456789"}]}
                        """, MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.create(request(attemptId, Channel.BRI_VA, null));

        assertThat(payment.providerRef()).isEqualTo("pr-1");
        assertThat(payment.instruction()).isEqualTo(new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, "8808123456789"));
        assertThat(payment.expiresAt()).isEqualTo(Instant.parse("2026-10-09T11:00:00Z"));
        server.verify();
    }

    @Test
    void ewalletSendsReturnUrlsAndRedirectsCustomer() {
        server.expect(requestTo("https://api.xendit.test/v3/payment_requests"))
                .andExpect(jsonPath("$.channel_code").value("DANA"))
                .andExpect(jsonPath("$.channel_properties.success_return_url").value("https://shop.test/return"))
                .andRespond(withSuccess("""
                        {"payment_request_id":"pr-2","status":"REQUIRES_ACTION",
                         "actions":[{"type":"REDIRECT_CUSTOMER","descriptor":"WEB_URL","value":"https://dana.test/pay"}]}
                        """, MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.create(request(UUID.randomUUID(), Channel.DANA, null));

        assertThat(payment.instruction()).isEqualTo(new Instruction(Instruction.Type.REDIRECT_URL, "https://dana.test/pay"));
    }

    @Test
    void ovoRequiresMobileNumber() {
        assertThatThrownBy(() -> gateway.create(request(UUID.randomUUID(), Channel.OVO, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gatewayErrorsBecomeGatewayException() {
        server.expect(requestTo("https://api.xendit.test/v3/payment_requests"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error_code\":\"CHANNEL_NOT_ACTIVATED\"}"));

        assertThatThrownBy(() -> gateway.create(request(UUID.randomUUID(), Channel.BRI_VA, null)))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("CHANNEL_NOT_ACTIVATED");
    }

    @Test
    void fetchesAndMapsStatus() {
        server.expect(requestTo("https://api.xendit.test/v3/payment_requests/pr-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"payment_request_id\":\"pr-1\",\"status\":\"SUCCEEDED\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.fetchStatus("pr-1")).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(XenditGateway.mapStatus("CANCELED")).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(XenditGateway.mapStatus("REQUIRES_ACTION")).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void acceptsWebhookWithValidCallbackToken() {
        UUID attemptId = UUID.randomUUID();
        String body = """
                {"event":"payment.capture","data":{"payment_id":"py-1","payment_request_id":"pr-1",
                 "reference_id":"%s","status":"SUCCEEDED"}}
                """.formatted(attemptId);

        WebhookNotification notification = gateway.parseWebhook(Map.of("X-Callback-Token", TOKEN), body);

        assertThat(notification.eventKey()).isEqualTo("payment.capture:py-1");
        assertThat(notification.providerRef()).isEqualTo("pr-1");
        assertThat(notification.attemptId()).isEqualTo(attemptId);
    }

    @Test
    void rejectsWebhookWithWrongOrMissingToken() {
        String body = "{\"event\":\"payment.capture\",\"data\":{\"payment_request_id\":\"pr-1\"}}";

        assertThatThrownBy(() -> gateway.parseWebhook(Map.of("x-callback-token", "guess"), body))
                .isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(Map.of(), body))
                .isInstanceOf(InvalidWebhookException.class);
    }

    private static PaymentRequest request(UUID attemptId, Channel channel, String mobile) {
        return new PaymentRequest(attemptId, "ORD-1", "Premium plan", 150_000, "Budi", mobile, channel,
                Instant.parse("2026-10-09T11:00:00Z"));
    }
}

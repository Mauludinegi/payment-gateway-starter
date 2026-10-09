package io.github.mauludinegi.payments.gateway.xendit;

import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.gateway.GatewayException;
import io.github.mauludinegi.payments.gateway.GatewayPayment;
import io.github.mauludinegi.payments.gateway.InvalidWebhookException;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.gateway.PaymentRequest;
import io.github.mauludinegi.payments.gateway.WebhookNotification;
import io.github.mauludinegi.payments.gateway.WebhookSecrets;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Instruction;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Xendit Payments API v3 (payment requests). */
@Component
public class XenditGateway implements PaymentGateway {

    static final String API_VERSION = "2024-11-11";

    private static final Map<Channel, String> CHANNEL_CODES = new EnumMap<>(Map.ofEntries(
            Map.entry(Channel.BCA_VA, "BCA_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.BNI_VA, "BNI_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.BRI_VA, "BRI_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.MANDIRI_VA, "MANDIRI_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.PERMATA_VA, "PERMATA_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.BSI_VA, "BSI_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.BSS_VA, "BSS_VIRTUAL_ACCOUNT"),
            Map.entry(Channel.QRIS, "QRIS"),
            Map.entry(Channel.OVO, "OVO"),
            Map.entry(Channel.DANA, "DANA"),
            Map.entry(Channel.SHOPEEPAY, "SHOPEEPAY"),
            Map.entry(Channel.LINKAJA, "LINKAJA"),
            Map.entry(Channel.INDOMARET, "INDOMARET"),
            Map.entry(Channel.ALFAMART, "ALFAMART")));

    private final PaymentsProperties.Xendit config;
    private final PaymentsProperties properties;
    private final RestClient http;
    private final JsonMapper json;

    public XenditGateway(RestClient.Builder builder, PaymentsProperties properties, JsonMapper json) {
        this.config = properties.xendit();
        this.properties = properties;
        this.json = json;
        String basic = Base64.getEncoder().encodeToString(((config.secretKey() == null ? "" : config.secretKey()) + ":").getBytes(StandardCharsets.UTF_8));
        this.http = builder.clone()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader("api-version", API_VERSION)
                .build();
    }

    @Override
    public Provider provider() {
        return Provider.XENDIT;
    }

    @Override
    public boolean isConfigured() {
        return notBlank(config.secretKey()) && notBlank(config.callbackToken());
    }

    @Override
    public Set<Channel> channels() {
        return CHANNEL_CODES.keySet();
    }

    @Override
    public GatewayPayment create(PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference_id", request.attemptId().toString());
        body.put("type", "PAY");
        body.put("country", "ID");
        body.put("currency", "IDR");
        body.put("request_amount", request.amount());
        body.put("capture_method", "AUTOMATIC");
        body.put("channel_code", CHANNEL_CODES.get(request.channel()));
        body.put("channel_properties", channelProperties(request));
        body.put("description", request.description());
        body.put("metadata", Map.of("order_reference", request.orderReference()));

        JsonNode response = call(() -> http.post().uri("/v3/payment_requests")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));

        String expires = response.path("channel_properties").path("expires_at").asString(null);
        return new GatewayPayment(
                response.path("payment_request_id").asString(),
                instruction(response.path("actions")),
                expires == null ? null : Instant.parse(expires));
    }

    @Override
    public PaymentStatus fetchStatus(String providerRef) {
        JsonNode response = call(() -> http.get().uri("/v3/payment_requests/{id}", providerRef)
                .retrieve()
                .body(JsonNode.class));
        return mapStatus(response.path("status").asString());
    }

    @Override
    public void cancel(String providerRef) {
        call(() -> http.post().uri("/v3/payment_requests/{id}/cancel", providerRef)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public WebhookNotification parseWebhook(Map<String, String> headers, String body) {
        if (!WebhookSecrets.matches(config.callbackToken(), WebhookSecrets.header(headers, "x-callback-token"))) {
            throw new InvalidWebhookException("Invalid Xendit callback token");
        }
        JsonNode payload = json.readTree(body);
        JsonNode data = payload.path("data");
        String paymentRequestId = data.path("payment_request_id").asString(null);
        if (paymentRequestId == null) {
            throw new InvalidWebhookException("Xendit webhook without payment_request_id");
        }
        String eventKey = payload.path("event").asString() + ":" + data.path("payment_id").asString(paymentRequestId);
        return new WebhookNotification(eventKey, paymentRequestId, parseUuid(data.path("reference_id").asString(null)));
    }

    private Map<String, Object> channelProperties(PaymentRequest request) {
        Map<String, Object> props = new LinkedHashMap<>();
        switch (request.channel().kind()) {
            case VIRTUAL_ACCOUNT -> {
                props.put("display_name", request.customerName());
                props.put("expires_at", request.expiresAt().toString());
            }
            case QR -> props.put("expires_at", request.expiresAt().toString());
            case RETAIL -> {
                props.put("payer_name", request.customerName());
                props.put("expires_at", request.expiresAt().toString());
            }
            case EWALLET -> {
                if (request.channel() == Channel.OVO) {
                    if (!notBlank(request.mobileNumber())) {
                        throw new IllegalArgumentException("OVO needs the customer's mobile number");
                    }
                    props.put("account_mobile_number", request.mobileNumber());
                } else {
                    props.put("success_return_url", returnUrlFor(request));
                    props.put("failure_return_url", returnUrlFor(request));
                }
            }
        }
        return props;
    }

    /** Picks what to show the customer from the v3 `actions` list. */
    static Instruction instruction(JsonNode actions) {
        for (JsonNode action : actions) {
            String descriptor = action.path("descriptor").asString();
            String value = action.path("value").asString();
            switch (descriptor) {
                case "VIRTUAL_ACCOUNT_NUMBER" -> { return new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, value); }
                case "QR_STRING" -> { return new Instruction(Instruction.Type.QR_STRING, value); }
                case "PAYMENT_CODE" -> { return new Instruction(Instruction.Type.PAYMENT_CODE, value); }
                case "WEB_URL", "DEEPLINK_URL" -> { return new Instruction(Instruction.Type.REDIRECT_URL, value); }
                default -> { }
            }
        }
        // OVO: the customer approves a push notification in the app, nothing to show.
        return new Instruction(Instruction.Type.PAYMENT_CODE, "Open the app to approve the payment");
    }

    static PaymentStatus mapStatus(String status) {
        return switch (status) {
            case "SUCCEEDED" -> PaymentStatus.SUCCEEDED;
            case "FAILED" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            case "CANCELED" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.PENDING;
        };
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new GatewayException("Xendit returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new GatewayException("Xendit is unreachable", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String returnUrlFor(PaymentRequest request) {
        return properties.returnUrlFor(request.orderId());
    }
}

package io.github.mauludinegi.payments.gateway.midtrans;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Midtrans Core API (charge, status, expire) with signature-verified HTTP notifications. */
@Component
public class MidtransGateway implements PaymentGateway {

    private static final Set<Channel> CHANNELS = EnumSet.of(
            Channel.BCA_VA, Channel.BNI_VA, Channel.BRI_VA, Channel.PERMATA_VA, Channel.MANDIRI_VA,
            Channel.QRIS, Channel.GOPAY, Channel.SHOPEEPAY, Channel.INDOMARET, Channel.ALFAMART);

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter MIDTRANS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentsProperties.Midtrans config;
    private final PaymentsProperties properties;
    private final RestClient http;
    private final JsonMapper json;

    public MidtransGateway(RestClient.Builder builder, PaymentsProperties properties, JsonMapper json) {
        this.config = properties.midtrans();
        this.properties = properties;
        this.json = json;
        String basic = Base64.getEncoder().encodeToString(((config.serverKey() == null ? "" : config.serverKey()) + ":").getBytes(StandardCharsets.UTF_8));
        this.http = builder.clone()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Provider provider() {
        return Provider.MIDTRANS;
    }

    @Override
    public boolean isConfigured() {
        return config.serverKey() != null && !config.serverKey().isBlank();
    }

    @Override
    public Set<Channel> channels() {
        return CHANNELS;
    }

    @Override
    public GatewayPayment create(PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_details", Map.of("order_id", request.attemptId().toString(), "gross_amount", request.amount()));
        body.put("customer_details", Map.of("first_name", request.customerName()));
        long minutes = Math.max(1, Duration.between(Instant.now(), request.expiresAt()).toMinutes());
        body.put("custom_expiry", Map.of("expiry_duration", minutes, "unit", "minute"));
        switch (request.channel()) {
            case BCA_VA, BNI_VA, BRI_VA, PERMATA_VA -> {
                body.put("payment_type", "bank_transfer");
                body.put("bank_transfer", Map.of("bank", bank(request.channel())));
            }
            case MANDIRI_VA -> {
                body.put("payment_type", "echannel");
                body.put("echannel", Map.of("bill_info1", "Payment:", "bill_info2", truncate(request.description(), 18)));
            }
            case QRIS -> body.put("payment_type", "qris");
            case GOPAY -> {
                body.put("payment_type", "gopay");
                body.put("gopay", Map.of("enable_callback", true, "callback_url", returnUrlFor(request)));
            }
            case SHOPEEPAY -> {
                body.put("payment_type", "shopeepay");
                body.put("shopeepay", Map.of("callback_url", returnUrlFor(request)));
            }
            case INDOMARET, ALFAMART -> {
                body.put("payment_type", "cstore");
                body.put("cstore", Map.of("store", request.channel().name().toLowerCase(), "message", truncate(request.description(), 20)));
            }
            default -> throw new IllegalArgumentException(request.channel().label() + " is not supported by Midtrans");
        }

        JsonNode response = call(() -> http.post().uri("/v2/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        String statusCode = response.path("status_code").asString("");
        if (!statusCode.startsWith("2")) {
            throw new GatewayException("Midtrans returned " + statusCode + ": " + response.path("status_message").asString());
        }
        return new GatewayPayment(request.attemptId().toString(), instruction(response), parseTime(response.path("expiry_time").asString(null)));
    }

    @Override
    public PaymentStatus fetchStatus(String providerRef) {
        JsonNode response = call(() -> http.get().uri("/v2/{orderId}/status", providerRef)
                .retrieve()
                .body(JsonNode.class));
        return mapStatus(response.path("transaction_status").asString(""), response.path("fraud_status").asString(""));
    }

    /** Midtrans "expire" is the call that stops a pending VA, QR, or store payment. */
    @Override
    public void cancel(String providerRef) {
        call(() -> http.post().uri("/v2/{orderId}/expire", providerRef)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public WebhookNotification parseWebhook(Map<String, String> headers, String body) {
        JsonNode payload = json.readTree(body);
        String orderId = payload.path("order_id").asString("");
        String expected = WebhookSecrets.sha512Hex(orderId
                + payload.path("status_code").asString("")
                + payload.path("gross_amount").asString("")
                + config.serverKey());
        if (!isConfigured() || !WebhookSecrets.matches(expected, payload.path("signature_key").asString(null))) {
            throw new InvalidWebhookException("Invalid Midtrans signature");
        }
        String eventKey = payload.path("transaction_id").asString(orderId) + ":" + payload.path("transaction_status").asString();
        return new WebhookNotification(eventKey, orderId, parseUuid(orderId));
    }

    static Instruction instruction(JsonNode response) {
        JsonNode va = response.path("va_numbers");
        if (va.isArray() && !va.isEmpty()) {
            return new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, va.get(0).path("va_number").asString());
        }
        if (response.hasNonNull("permata_va_number")) {
            return new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, response.path("permata_va_number").asString());
        }
        if (response.hasNonNull("bill_key")) {
            return new Instruction(Instruction.Type.PAYMENT_CODE,
                    "Biller code " + response.path("biller_code").asString() + ", bill key " + response.path("bill_key").asString());
        }
        if (response.hasNonNull("payment_code")) {
            return new Instruction(Instruction.Type.PAYMENT_CODE, response.path("payment_code").asString());
        }
        if (response.hasNonNull("qr_string")) {
            return new Instruction(Instruction.Type.QR_STRING, response.path("qr_string").asString());
        }
        String deeplink = null;
        String qrImage = null;
        for (JsonNode action : response.path("actions")) {
            switch (action.path("name").asString()) {
                case "deeplink-redirect" -> deeplink = action.path("url").asString();
                case "generate-qr-code" -> qrImage = action.path("url").asString();
                default -> { }
            }
        }
        String url = deeplink != null ? deeplink : qrImage;
        if (url == null) {
            throw new GatewayException("Midtrans response has no payment instruction");
        }
        return new Instruction(Instruction.Type.REDIRECT_URL, url);
    }

    static PaymentStatus mapStatus(String transactionStatus, String fraudStatus) {
        return switch (transactionStatus) {
            case "settlement" -> PaymentStatus.SUCCEEDED;
            case "capture" -> "accept".equals(fraudStatus) ? PaymentStatus.SUCCEEDED : PaymentStatus.PENDING;
            case "deny", "failure" -> PaymentStatus.FAILED;
            case "cancel" -> PaymentStatus.CANCELLED;
            case "expire" -> PaymentStatus.EXPIRED;
            default -> PaymentStatus.PENDING;
        };
    }

    private static String bank(Channel channel) {
        return switch (channel) {
            case BCA_VA -> "bca";
            case BNI_VA -> "bni";
            case BRI_VA -> "bri";
            case PERMATA_VA -> "permata";
            default -> throw new IllegalArgumentException(channel.name());
        };
    }

    private static Instant parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, MIDTRANS_TIME).atZone(JAKARTA).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new GatewayException("Midtrans returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new GatewayException("Midtrans is unreachable", e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String returnUrlFor(PaymentRequest request) {
        return properties.returnUrlFor(request.orderId());
    }
}

package io.github.mauludinegi.payments.gateway.simulator;

import io.github.mauludinegi.payments.config.PaymentsProperties;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A stand-in gateway so the whole flow runs without API keys. It behaves like a real one:
 * webhooks are HMAC-signed and the status is re-checked through {@link #fetchStatus}.
 */
@Component
public class SimulatorGateway implements PaymentGateway {

    public static final String SIGNATURE_HEADER = "X-Simulator-Signature";

    private final PaymentsProperties.Simulator config;
    private final JsonMapper json;
    private final Map<String, PaymentStatus> statuses = new ConcurrentHashMap<>();

    public SimulatorGateway(PaymentsProperties properties, JsonMapper json) {
        this.config = properties.simulator();
        this.json = json;
    }

    @Override
    public Provider provider() {
        return Provider.SIMULATOR;
    }

    @Override
    public boolean isConfigured() {
        return config.enabled();
    }

    @Override
    public Set<Channel> channels() {
        return EnumSet.allOf(Channel.class);
    }

    @Override
    public GatewayPayment create(PaymentRequest request) {
        String ref = "sim-" + request.attemptId();
        statuses.put(ref, PaymentStatus.PENDING);
        Instruction instruction = switch (request.channel().kind()) {
            case VIRTUAL_ACCOUNT -> new Instruction(Instruction.Type.VIRTUAL_ACCOUNT_NUMBER, "8808" + digits(12));
            case QR -> new Instruction(Instruction.Type.QR_STRING, "00020101021226590013ID.SIMULATOR" + digits(16) + "5303360540" + request.amount() + "6304ABCD");
            case EWALLET -> new Instruction(Instruction.Type.REDIRECT_URL, "https://simulator.invalid/ewallet/" + ref);
            case RETAIL -> new Instruction(Instruction.Type.PAYMENT_CODE, "SIM" + digits(10));
        };
        return new GatewayPayment(ref, instruction, request.expiresAt());
    }

    @Override
    public PaymentStatus fetchStatus(String providerRef) {
        return statuses.getOrDefault(providerRef, PaymentStatus.PENDING);
    }

    @Override
    public void cancel(String providerRef) {
        statuses.computeIfPresent(providerRef, (k, v) -> v == PaymentStatus.PENDING ? PaymentStatus.CANCELLED : v);
    }

    @Override
    public WebhookNotification parseWebhook(Map<String, String> headers, String body) {
        if (!WebhookSecrets.matches(sign(body), WebhookSecrets.header(headers, SIGNATURE_HEADER))) {
            throw new InvalidWebhookException("Invalid simulator signature");
        }
        JsonNode payload = json.readTree(body);
        String ref = payload.path("payment_ref").asString();
        UUID attemptId = ref.startsWith("sim-") ? UUID.fromString(ref.substring(4)) : null;
        return new WebhookNotification(payload.path("event_id").asString(), ref, attemptId);
    }

    /** Moves a payment to a new status and returns the signed webhook the "gateway" would send. */
    public SignedWebhook simulate(String providerRef, PaymentStatus status) {
        statuses.put(providerRef, status);
        String body = json.writeValueAsString(Map.of(
                "event_id", UUID.randomUUID().toString(),
                "payment_ref", providerRef,
                "status", status.name()));
        return new SignedWebhook(body, sign(body));
    }

    public record SignedWebhook(String body, String signature) {
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }
}

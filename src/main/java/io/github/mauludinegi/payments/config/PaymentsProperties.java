package io.github.mauludinegi.payments.config;

import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * @param defaultProvider gateway used for channels without a routing entry
 * @param routing         per-channel gateway, e.g. send OVO to Xendit and GoPay to Midtrans
 * @param routes          the same as one string, {@code DANA:XENDIT,BSI_VA:XENDIT}, for environment variables;
 *                        entries here win over {@code routing}
 * @param orderTtl        how long an order can be paid
 * @param paymentTtl      how long one payment attempt (VA number, QR, code) stays valid
 * @param returnUrl       where e-wallets send the customer back after paying; {orderId} is replaced
 */
@ConfigurationProperties("payments")
public record PaymentsProperties(
        Provider defaultProvider,
        Map<Channel, Provider> routing,
        Duration orderTtl,
        Duration paymentTtl,
        String returnUrl,
        Xendit xendit,
        Midtrans midtrans,
        Simulator simulator,
        String routes) {

    public PaymentsProperties {
        defaultProvider = defaultProvider == null ? Provider.SIMULATOR : defaultProvider;
        routing = withRoutes(routing, routes);
        orderTtl = orderTtl == null ? Duration.ofHours(24) : orderTtl;
        paymentTtl = paymentTtl == null ? Duration.ofHours(1) : paymentTtl;
        returnUrl = returnUrl == null ? "http://localhost:3000/orders/{orderId}" : returnUrl;
        xendit = xendit == null ? new Xendit(null, null, null) : xendit;
        midtrans = midtrans == null ? new Midtrans(null, null) : midtrans;
        simulator = simulator == null ? new Simulator(true, null) : simulator;
    }

    private static Map<Channel, Provider> withRoutes(Map<Channel, Provider> routing, String routes) {
        Map<Channel, Provider> merged = new EnumMap<>(Channel.class);
        if (routing != null) {
            merged.putAll(routing);
        }
        if (routes == null || routes.isBlank()) {
            return merged;
        }
        for (String entry : routes.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("payments.routes entry '" + entry.trim() + "' must look like CHANNEL:PROVIDER");
            }
            merged.put(Channel.valueOf(parts[0].trim().toUpperCase()), Provider.valueOf(parts[1].trim().toUpperCase()));
        }
        return merged;
    }

    public String returnUrlFor(UUID orderId) {
        return returnUrl.replace("{orderId}", orderId.toString());
    }

    public Provider providerFor(Channel channel) {
        return routing.getOrDefault(channel, defaultProvider);
    }

    public record Xendit(String secretKey, String callbackToken, String baseUrl) {
        public Xendit {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.xendit.co" : baseUrl;
        }
    }

    public record Midtrans(String serverKey, String baseUrl) {
        public Midtrans {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.sandbox.midtrans.com" : baseUrl;
        }
    }

    public record Simulator(boolean enabled, String secret) {
        public Simulator {
            secret = secret == null || secret.isBlank() ? "local-simulator-secret" : secret;
        }
    }
}

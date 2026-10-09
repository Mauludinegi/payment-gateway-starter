package io.github.mauludinegi.payments.config;

import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * @param defaultProvider gateway used for channels without a routing entry
 * @param routing         per-channel gateway, e.g. send OVO to Xendit and GoPay to Midtrans
 * @param orderTtl        how long an order can be paid
 * @param paymentTtl      how long one payment attempt (VA number, QR, code) stays valid
 * @param returnUrl       where e-wallets send the customer back after paying
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
        Simulator simulator) {

    public PaymentsProperties {
        defaultProvider = defaultProvider == null ? Provider.SIMULATOR : defaultProvider;
        routing = routing == null ? new EnumMap<>(Channel.class) : routing;
        orderTtl = orderTtl == null ? Duration.ofHours(24) : orderTtl;
        paymentTtl = paymentTtl == null ? Duration.ofHours(1) : paymentTtl;
        returnUrl = returnUrl == null ? "http://localhost:8080/" : returnUrl;
        xendit = xendit == null ? new Xendit(null, null, null) : xendit;
        midtrans = midtrans == null ? new Midtrans(null, null) : midtrans;
        simulator = simulator == null ? new Simulator(true, null) : simulator;
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

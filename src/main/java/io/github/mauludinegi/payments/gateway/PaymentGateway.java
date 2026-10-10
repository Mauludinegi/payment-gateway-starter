package io.github.mauludinegi.payments.gateway;

import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;

import java.util.Map;
import java.util.Set;

public interface PaymentGateway {

    Provider provider();

    /** False when the API keys are missing; the gateway then refuses payments and webhooks. */
    boolean isConfigured();

    Set<Channel> channels();

    /**
     * Creates the payment for {@code request.attemptId()}. Throw {@link GatewayUnavailableException}
     * when the outcome is unknown (timeout, 5xx), {@link GatewayException} when it was refused.
     */
    GatewayPayment create(PaymentRequest request);

    /**
     * Whether {@link #create} may be called again for an attempt whose first call had an unknown
     * outcome. True when a repeat returns the same payment, or when a duplicate is harmless because
     * the customer can only pay what they were shown.
     */
    default boolean canRetryCreate(Channel channel) {
        return true;
    }

    /** Asks the gateway for the current status. Webhooks are only trusted after this re-check. */
    PaymentStatus fetchStatus(String providerRef);

    /** Best effort: stops an unpaid payment so the customer cannot pay twice after changing method. */
    void cancel(String providerRef);

    /** Verifies the webhook came from the gateway and extracts what it refers to. */
    WebhookNotification parseWebhook(Map<String, String> headers, String body);
}

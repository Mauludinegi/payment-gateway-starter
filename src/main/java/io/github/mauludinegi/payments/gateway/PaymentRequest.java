package io.github.mauludinegi.payments.gateway;

import io.github.mauludinegi.payments.payment.Channel;

import java.time.Instant;
import java.util.UUID;

/** Everything a gateway needs to create one payment. The attempt id doubles as the gateway reference id. */
public record PaymentRequest(
        UUID attemptId,
        UUID orderId,
        String orderReference,
        String description,
        long amount,
        String customerName,
        String mobileNumber,
        Channel channel,
        Instant expiresAt) {
}

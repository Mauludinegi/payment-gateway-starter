package io.github.mauludinegi.payments.gateway;

import io.github.mauludinegi.payments.payment.Instruction;

import java.time.Instant;

/** @param expiresAt when the gateway stops accepting this payment, or null to keep our own expiry */
public record GatewayPayment(String providerRef, Instruction instruction, Instant expiresAt) {
}

package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.payment.Channel;

import java.util.UUID;

public record OrderPaidEvent(UUID orderId, String reference, long amount, UUID attemptId, Channel channel) {
}

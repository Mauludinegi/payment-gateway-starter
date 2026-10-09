package io.github.mauludinegi.payments.service;

import java.util.UUID;

/** Something customers can see on an order changed: its status, or its payment's status or instructions. */
public record OrderChangedEvent(UUID orderId) {
}

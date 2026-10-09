package io.github.mauludinegi.payments.order;

import java.util.UUID;

public record UserOrderTotal(UUID userId, long orders, long spent) {
}

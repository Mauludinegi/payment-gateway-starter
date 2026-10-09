package io.github.mauludinegi.payments.order;

public record StatusTotal(OrderStatus status, long count, long amount) {
}

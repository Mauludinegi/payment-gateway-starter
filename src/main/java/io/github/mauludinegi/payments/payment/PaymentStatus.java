package io.github.mauludinegi.payments.payment;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }
}

package io.github.mauludinegi.payments.service;

/** Another payment for the order is still being confirmed with the gateway. */
public class PaymentInProgressException extends IllegalStateException {

    public PaymentInProgressException(String message) {
        super(message);
    }
}

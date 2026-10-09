package io.github.mauludinegi.payments.payment;

public enum Provider {
    XENDIT,
    MIDTRANS,
    /** Local gateway for demos and tests: no keys, payments are completed from the demo page. */
    SIMULATOR
}

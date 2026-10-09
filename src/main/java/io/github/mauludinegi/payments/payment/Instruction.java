package io.github.mauludinegi.payments.payment;

/** What the customer needs to complete the payment: a VA number, a QR string, a redirect URL, or a payment code. */
public record Instruction(Type type, String value) {

    public enum Type { VIRTUAL_ACCOUNT_NUMBER, QR_STRING, REDIRECT_URL, PAYMENT_CODE }
}

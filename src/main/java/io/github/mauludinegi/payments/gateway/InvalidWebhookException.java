package io.github.mauludinegi.payments.gateway;

public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String message) {
        super(message);
    }
}

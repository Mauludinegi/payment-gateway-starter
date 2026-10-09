package io.github.mauludinegi.payments.gateway;

/** The gateway rejected a request or could not be reached. */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

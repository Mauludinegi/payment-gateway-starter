package io.github.mauludinegi.payments.gateway;

/**
 * The gateway timed out, could not be reached, or failed on its side, so we do not know whether the
 * request took effect. Unlike a plain {@link GatewayException}, this must not be treated as a rejection.
 */
public class GatewayUnavailableException extends GatewayException {

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GatewayUnavailableException(String message) {
        super(message);
    }
}

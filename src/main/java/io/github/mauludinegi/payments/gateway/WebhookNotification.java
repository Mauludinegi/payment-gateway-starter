package io.github.mauludinegi.payments.gateway;

import java.util.UUID;

/**
 * A verified webhook.
 *
 * @param eventKey    unique per gateway event, used to process each event once
 * @param providerRef the gateway's id for the payment
 * @param attemptId   our attempt id echoed back by the gateway, if present
 */
public record WebhookNotification(String eventKey, String providerRef, UUID attemptId) {
}

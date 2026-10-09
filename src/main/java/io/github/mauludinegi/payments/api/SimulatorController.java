package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.gateway.simulator.SimulatorGateway;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.CheckoutService;
import io.github.mauludinegi.payments.webhook.WebhookService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Demo only: plays the gateway's part and sends a signed webhook through the normal webhook path. */
@RestController
@RequestMapping("/api/simulator")
@ConditionalOnProperty(name = "payments.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatorController {

    private final CheckoutService checkout;
    private final SimulatorGateway simulator;
    private final WebhookService webhooks;

    public SimulatorController(CheckoutService checkout, SimulatorGateway simulator, WebhookService webhooks) {
        this.checkout = checkout;
        this.simulator = simulator;
        this.webhooks = webhooks;
    }

    @PostMapping("/orders/{orderId}/{outcome}")
    public Map<String, String> simulate(@PathVariable UUID orderId, @PathVariable String outcome) {
        PaymentAttempt attempt = checkout.view(orderId).payment();
        if (attempt == null || attempt.getProvider() != Provider.SIMULATOR) {
            throw new IllegalStateException("The latest payment of this order is not a simulator payment");
        }
        PaymentStatus status = switch (outcome) {
            case "pay" -> PaymentStatus.SUCCEEDED;
            case "fail" -> PaymentStatus.FAILED;
            case "expire" -> PaymentStatus.EXPIRED;
            default -> throw new IllegalArgumentException("Outcome must be pay, fail, or expire");
        };
        SimulatorGateway.SignedWebhook webhook = simulator.simulate(attempt.getProviderRef(), status);
        WebhookService.Result result = webhooks.handle(Provider.SIMULATOR,
                Map.of(SimulatorGateway.SIGNATURE_HEADER, webhook.signature()), webhook.body());
        return Map.of("result", result.name());
    }
}

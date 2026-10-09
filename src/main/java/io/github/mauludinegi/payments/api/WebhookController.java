package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.webhook.WebhookService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Point the gateway dashboards here: /webhooks/xendit and /webhooks/midtrans.
 * Duplicates and unknown payments still get 200 so the gateway stops retrying them.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    public Map<String, String> receive(@PathVariable String provider, @RequestHeader Map<String, String> headers, @RequestBody String body) {
        WebhookService.Result result = webhooks.handle(Provider.valueOf(provider.toUpperCase()), headers, body);
        return Map.of("result", result.name());
    }
}

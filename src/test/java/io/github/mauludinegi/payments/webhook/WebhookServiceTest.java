package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.gateway.GatewayRegistry;
import io.github.mauludinegi.payments.gateway.PaymentGateway;
import io.github.mauludinegi.payments.gateway.WebhookNotification;
import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Instruction;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import io.github.mauludinegi.payments.service.PaymentStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final GatewayRegistry gateways = mock(GatewayRegistry.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final WebhookEventRepository events = mock(WebhookEventRepository.class);
    private final PaymentStatusService statuses = mock(PaymentStatusService.class);
    private WebhookService service;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        service = new WebhookService(gateways, attempts, events, statuses,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));
        when(gateways.get(Provider.SIMULATOR)).thenReturn(gateway);
        Order order = new Order(UUID.randomUUID(), "ORD-1", "Plan", 100_000, "Budi", "budi@example.com", NOW, NOW.plus(Duration.ofHours(1)));
        attempt = new PaymentAttempt(order, Provider.SIMULATOR, Channel.QRIS, null, null, NOW, NOW.plus(Duration.ofHours(1)));
        attempt.attachGatewayPayment("sim-1", new Instruction(Instruction.Type.QR_STRING, "qr"), null, NOW);
        when(gateway.parseWebhook(any(), any())).thenReturn(new WebhookNotification("evt-1", "sim-1", attempt.getId()));
        when(attempts.findWithOrder(attempt.getId())).thenReturn(Optional.of(attempt));
        when(gateway.fetchStatus("sim-1")).thenReturn(PaymentStatus.SUCCEEDED);
    }

    @Test
    void integrityViolationFromARaceWithTheSameEventIsADuplicate() {
        when(events.existsByProviderAndEventKey(Provider.SIMULATOR, "evt-1")).thenReturn(false, true);
        when(events.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_webhook_events"));

        assertThat(service.handle(Provider.SIMULATOR, Map.of(), "{}")).isEqualTo(WebhookService.Result.DUPLICATE);
    }

    @Test
    void otherIntegrityViolationsFailSoTheGatewayRetries() {
        when(events.existsByProviderAndEventKey(Provider.SIMULATOR, "evt-1")).thenReturn(false);
        when(events.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("value too long"));

        assertThatThrownBy(() -> service.handle(Provider.SIMULATOR, Map.of(), "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

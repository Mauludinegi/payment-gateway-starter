package io.github.mauludinegi.payments.service;

import io.github.mauludinegi.payments.order.Order;
import io.github.mauludinegi.payments.order.OrderStatus;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.PaymentAttempt;
import io.github.mauludinegi.payments.payment.PaymentAttemptRepository;
import io.github.mauludinegi.payments.payment.PaymentStatus;
import io.github.mauludinegi.payments.payment.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PaymentStatusService service = new PaymentStatusService(attempts, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order("ORD-1", "Plan", 150_000, "Budi", NOW, NOW.plus(Duration.ofDays(1)));
    }

    private PaymentAttempt attempt(PaymentStatus status) {
        PaymentAttempt attempt = new PaymentAttempt(order, Provider.SIMULATOR, Channel.BRI_VA, NOW, NOW.plus(Duration.ofHours(1)));
        attempt.changeStatus(status, NOW);
        when(attempts.findWithOrder(attempt.getId())).thenReturn(Optional.of(attempt));
        return attempt;
    }

    @Test
    void successMarksOrderPaidAndPublishesOnce() {
        PaymentAttempt attempt = attempt(PaymentStatus.PENDING);

        assertThat(service.apply(attempt.getId(), PaymentStatus.SUCCEEDED)).isEqualTo(PaymentStatusService.Outcome.UPDATED);
        assertThat(service.apply(attempt.getId(), PaymentStatus.SUCCEEDED)).isEqualTo(PaymentStatusService.Outcome.UNCHANGED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        verify(events, times(1)).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void succeededPaymentIsNeverDowngraded() {
        PaymentAttempt attempt = attempt(PaymentStatus.PENDING);
        service.apply(attempt.getId(), PaymentStatus.SUCCEEDED);

        assertThat(service.apply(attempt.getId(), PaymentStatus.EXPIRED)).isEqualTo(PaymentStatusService.Outcome.IGNORED);
        assertThat(service.apply(attempt.getId(), PaymentStatus.FAILED)).isEqualTo(PaymentStatusService.Outcome.IGNORED);

        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void latePaymentOnCancelledAttemptStillCounts() {
        PaymentAttempt attempt = attempt(PaymentStatus.CANCELLED);

        assertThat(service.apply(attempt.getId(), PaymentStatus.SUCCEEDED)).isEqualTo(PaymentStatusService.Outcome.UPDATED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void finalFailureIsNotOverwrittenByAnotherFailure() {
        PaymentAttempt attempt = attempt(PaymentStatus.EXPIRED);

        assertThat(service.apply(attempt.getId(), PaymentStatus.CANCELLED)).isEqualTo(PaymentStatusService.Outcome.IGNORED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void secondSuccessfulPaymentDoesNotRepublish() {
        PaymentAttempt first = attempt(PaymentStatus.PENDING);
        PaymentAttempt second = attempt(PaymentStatus.CANCELLED);
        service.apply(first.getId(), PaymentStatus.SUCCEEDED);

        assertThat(service.apply(second.getId(), PaymentStatus.SUCCEEDED)).isEqualTo(PaymentStatusService.Outcome.UPDATED);

        verify(events, times(1)).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void pendingReportDoesNothing() {
        PaymentAttempt attempt = attempt(PaymentStatus.PENDING);

        assertThat(service.apply(attempt.getId(), PaymentStatus.PENDING)).isEqualTo(PaymentStatusService.Outcome.UNCHANGED);
        verify(events, never()).publishEvent(any());
    }
}

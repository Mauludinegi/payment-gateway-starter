package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndEventKey(Provider provider, String eventKey);

    List<WebhookEvent> findByAttemptIdInOrderByIdDesc(Collection<UUID> attemptIds);
}

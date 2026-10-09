package io.github.mauludinegi.payments.webhook;

import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndEventKey(Provider provider, String eventKey);
}

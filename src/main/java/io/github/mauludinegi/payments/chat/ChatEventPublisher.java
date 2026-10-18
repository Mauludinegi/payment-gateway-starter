package io.github.mauludinegi.payments.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    private final Optional<RabbitTemplate> rabbitTemplate;
    private final ChatEventHub eventHub;
    private final ChatFcmWorker fcmWorker;
    private final boolean rabbitEnabled;

    public ChatEventPublisher(
            @Autowired(required = false) RabbitTemplate rabbitTemplate,
            ChatEventHub eventHub,
            ChatFcmWorker fcmWorker,
            @Value("${chat.rabbitmq.enabled:false}") boolean rabbitEnabled
    ) {
        this.rabbitTemplate = Optional.ofNullable(rabbitTemplate);
        this.eventHub = eventHub;
        this.fcmWorker = fcmWorker;
        this.rabbitEnabled = rabbitEnabled;
    }

    public void publish(ChatMessageEvent event) {
        if (rabbitEnabled && rabbitTemplate.isPresent()) {
            try {
                log.debug("Publishing chat message event to RabbitMQ: {}", event.messageId());
                rabbitTemplate.get().convertAndSend(ChatRabbitConfig.EXCHANGE, ChatRabbitConfig.ROUTING_KEY, event);
                return;
            } catch (Exception e) {
                log.warn("Failed to publish chat message to RabbitMQ, falling back to local dispatch: {}", e.getMessage());
            }
        }

        // Local fallback (when RabbitMQ is disabled or during tests)
        eventHub.broadcast(event);
        fcmWorker.sendPushForMessage(event);
    }
}

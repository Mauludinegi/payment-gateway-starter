package io.github.mauludinegi.payments.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat.rabbitmq.enabled", havingValue = "true")
public class ChatRabbitConsumers {

    private static final Logger log = LoggerFactory.getLogger(ChatRabbitConsumers.class);

    private final ChatEventHub eventHub;
    private final ChatFcmWorker fcmWorker;

    public ChatRabbitConsumers(ChatEventHub eventHub, ChatFcmWorker fcmWorker) {
        this.eventHub = eventHub;
        this.fcmWorker = fcmWorker;
    }

    @RabbitListener(queues = ChatRabbitConfig.SSE_QUEUE)
    public void onSseEvent(ChatMessageEvent event) {
        log.debug("RabbitMQ SSE consumer received message for room: {}", event.roomId());
        eventHub.broadcast(event);
    }

    @RabbitListener(queues = ChatRabbitConfig.FCM_QUEUE)
    public void onFcmEvent(ChatMessageEvent event) {
        log.debug("RabbitMQ FCM consumer received message for room: {}", event.roomId());
        fcmWorker.sendPushForMessage(event);
    }
}

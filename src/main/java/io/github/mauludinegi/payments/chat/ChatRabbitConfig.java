package io.github.mauludinegi.payments.chat;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "chat.rabbitmq.enabled", havingValue = "true")
public class ChatRabbitConfig {

    public static final String EXCHANGE = "chat.exchange";
    public static final String SSE_QUEUE = "chat.sse.queue";
    public static final String FCM_QUEUE = "chat.fcm.queue";
    public static final String ROUTING_KEY = "chat.message.created";

    @Bean
    TopicExchange chatExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue chatSseQueue() {
        return new Queue(SSE_QUEUE, true);
    }

    @Bean
    Queue chatFcmQueue() {
        return new Queue(FCM_QUEUE, true);
    }

    @Bean
    Binding chatSseBinding(Queue chatSseQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(chatSseQueue).to(chatExchange).with("chat.message.#");
    }

    @Bean
    Binding chatFcmBinding(Queue chatFcmQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(chatFcmQueue).to(chatExchange).with("chat.message.#");
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}

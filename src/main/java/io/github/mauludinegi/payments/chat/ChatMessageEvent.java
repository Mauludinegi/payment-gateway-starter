package io.github.mauludinegi.payments.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record ChatMessageEvent(
        UUID roomId,
        UUID id,
        UUID customerId,
        UUID senderId,
        String senderRole,
        String senderName,
        String text,
        Instant createdAt
) implements Serializable {

    @JsonProperty("messageId")
    public UUID messageId() {
        return id;
    }

    public static ChatMessageEvent of(ChatMessage msg, UUID customerId) {
        return new ChatMessageEvent(
                msg.getRoomId(),
                msg.getId(),
                customerId,
                msg.getSenderId(),
                msg.getSenderRole(),
                msg.getSenderName(),
                msg.getText(),
                msg.getCreatedAt()
        );
    }
}

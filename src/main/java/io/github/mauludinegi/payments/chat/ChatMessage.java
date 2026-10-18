package io.github.mauludinegi.payments.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID roomId;

    @Column(nullable = false)
    private UUID senderId;

    @Column(nullable = false, length = 20)
    private String senderRole;

    @Column(nullable = false, length = 100)
    private String senderName;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(UUID roomId, UUID senderId, String senderRole, String senderName, String text, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderRole = senderRole;
        this.senderName = senderName;
        this.text = text;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getRoomId() { return roomId; }
    public UUID getSenderId() { return senderId; }
    public String getSenderRole() { return senderRole; }
    public String getSenderName() { return senderName; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
}

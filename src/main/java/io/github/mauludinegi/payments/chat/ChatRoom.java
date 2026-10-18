package io.github.mauludinegi.payments.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_rooms")
public class ChatRoom {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    protected ChatRoom() {
    }

    public ChatRoom(UUID customerId, Instant now) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.status = "OPEN";
        this.createdAt = now;
        this.lastMessageAt = now;
    }

    public void updateLastMessageAt(Instant now) {
        this.lastMessageAt = now;
    }

    public void close() {
        this.status = "CLOSED";
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
}

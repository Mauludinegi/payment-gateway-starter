package io.github.mauludinegi.payments.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_fcm_tokens")
public class UserFcmToken {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true, length = 500)
    private String token;

    private String deviceInfo;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserFcmToken() {
    }

    public UserFcmToken(UUID userId, String token, String deviceInfo, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.token = token;
        this.deviceInfo = deviceInfo;
        this.updatedAt = now;
    }

    public void update(UUID userId, String deviceInfo, Instant now) {
        this.userId = userId;
        this.deviceInfo = deviceInfo;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getToken() { return token; }
    public String getDeviceInfo() { return deviceInfo; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package io.github.mauludinegi.payments.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    /** Firebase uid for Google accounts, {@code dev:<email>} for the local dev sign-in. */
    @Column(nullable = false, unique = true)
    private String externalId;

    private String email;

    @Column(nullable = false)
    private String name;

    private String pictureUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastLoginAt;

    protected User() {
    }

    public User(String externalId, Instant now) {
        this.id = UUID.randomUUID();
        this.externalId = externalId;
        this.role = Role.CUSTOMER;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    /** Profile details follow the identity provider on every sign-in. */
    public void signedIn(String email, String name, String pictureUrl, Instant now) {
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
        this.lastLoginAt = now;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public UUID getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPictureUrl() { return pictureUrl; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
}

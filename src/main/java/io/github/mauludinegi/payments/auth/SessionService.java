package io.github.mauludinegi.payments.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque bearer sessions. The token goes to the client once; the store only ever sees its SHA-256,
 * so a leaked Redis dump cannot be replayed as a login.
 */
@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TOKEN_LENGTH = 100;

    private final SessionStore store;
    private final Duration ttl;
    private final Clock clock;

    public SessionService(SessionStore store, AuthProperties properties, Clock clock) {
        this.store = store;
        this.ttl = properties.sessionTtl();
        this.clock = clock;
    }

    public record Session(String token, Instant expiresAt) {
    }

    public Session create(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(key(token), userId, ttl);
        return new Session(token, clock.instant().plus(ttl));
    }

    public Optional<UUID> resolve(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        return store.find(key(token));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank() && token.length() <= MAX_TOKEN_LENGTH) {
            store.delete(key(token));
        }
    }

    private static String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "session:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

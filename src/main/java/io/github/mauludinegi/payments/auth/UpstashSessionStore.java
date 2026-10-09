package io.github.mauludinegi.payments.auth;

import io.github.mauludinegi.payments.redis.UpstashClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Sessions in Upstash Redis, so every app instance sees the same sessions. */
public class UpstashSessionStore implements SessionStore {

    private final UpstashClient redis;

    public UpstashSessionStore(UpstashClient redis) {
        this.redis = redis;
    }

    @Override
    public void save(String key, UUID userId, Duration ttl) {
        redis.command("SET", key, userId.toString(), "EX", String.valueOf(ttl.toSeconds()));
    }

    @Override
    public Optional<UUID> find(String key) {
        JsonNode result = redis.command("GET", key);
        return result == null || result.isNull() ? Optional.empty() : Optional.of(UUID.fromString(result.asString()));
    }

    @Override
    public void delete(String key) {
        redis.command("DEL", key);
    }
}

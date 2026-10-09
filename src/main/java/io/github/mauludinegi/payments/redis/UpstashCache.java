package io.github.mauludinegi.payments.redis;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

public class UpstashCache implements KeyValueCache {

    private final UpstashClient redis;

    public UpstashCache(UpstashClient redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        JsonNode result = redis.command("GET", key);
        return result == null || result.isNull() ? Optional.empty() : Optional.of(result.asString());
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.command("SET", key, value, "EX", String.valueOf(ttl.toSeconds()));
    }

    @Override
    public void delete(String key) {
        redis.command("DEL", key);
    }
}

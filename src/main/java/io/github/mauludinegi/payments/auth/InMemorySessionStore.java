package io.github.mauludinegi.payments.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance fallback when Redis is not configured; sessions are lost on restart. */
public class InMemorySessionStore implements SessionStore {

    private record Entry(UUID userId, Instant expiresAt) {
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySessionStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(String key, UUID userId, Duration ttl) {
        Instant now = clock.instant();
        sessions.values().removeIf(e -> e.expiresAt().isBefore(now));
        sessions.put(key, new Entry(userId, now.plus(ttl)));
    }

    @Override
    public Optional<UUID> find(String key) {
        Entry entry = sessions.get(key);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    @Override
    public void delete(String key) {
        sessions.remove(key);
    }
}

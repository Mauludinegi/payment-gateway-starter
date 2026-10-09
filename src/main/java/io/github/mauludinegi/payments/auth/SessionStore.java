package io.github.mauludinegi.payments.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Maps a hashed session key to a user until it expires. */
public interface SessionStore {

    void save(String key, UUID userId, Duration ttl);

    Optional<UUID> find(String key);

    void delete(String key);
}

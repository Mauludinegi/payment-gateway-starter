package io.github.mauludinegi.payments.redis;

import java.time.Duration;
import java.util.Optional;

/** A string cache with expiry: Upstash Redis when configured, otherwise this instance's memory. */
public interface KeyValueCache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    void delete(String key);
}

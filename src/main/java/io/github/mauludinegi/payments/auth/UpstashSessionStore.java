package io.github.mauludinegi.payments.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sessions in Upstash Redis over its REST API, so every app instance sees the same sessions. */
public class UpstashSessionStore implements SessionStore {

    private final RestClient http;

    public UpstashSessionStore(RestClient.Builder builder, AuthProperties.Upstash upstash) {
        this.http = builder.clone()
                .baseUrl(upstash.restUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + upstash.restToken())
                .build();
    }

    @Override
    public void save(String key, UUID userId, Duration ttl) {
        command("SET", key, userId.toString(), "EX", String.valueOf(ttl.toSeconds()));
    }

    @Override
    public Optional<UUID> find(String key) {
        JsonNode result = command("GET", key);
        return result == null || result.isNull() ? Optional.empty() : Optional.of(UUID.fromString(result.asString()));
    }

    @Override
    public void delete(String key) {
        command("DEL", key);
    }

    private JsonNode command(String... args) {
        JsonNode response = http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(args))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.has("error")) {
            throw new UpstashException("Upstash " + args[0] + " failed: "
                    + (response == null ? "empty response" : response.path("error").asString()));
        }
        return response.get("result");
    }

    public static class UpstashException extends RuntimeException {
        public UpstashException(String message) {
            super(message);
        }
    }
}

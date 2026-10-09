package io.github.mauludinegi.payments.redis;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** Redis commands over Upstash's REST API: one HTTP call per command, no connection pool to manage. */
public class UpstashClient {

    private final RestClient http;

    public UpstashClient(RestClient.Builder builder, UpstashProperties upstash) {
        this.http = builder.clone()
                .baseUrl(upstash.restUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + upstash.restToken())
                .build();
    }

    /** Runs a command such as {@code SET key value EX 60} and returns its {@code result}. */
    public JsonNode command(String... args) {
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

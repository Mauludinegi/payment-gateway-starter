package io.github.mauludinegi.payments.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Upstash Redis REST credentials, shared by sessions and the catalogue cache; both fall back to memory when blank. */
@ConfigurationProperties("upstash")
public record UpstashProperties(String restUrl, String restToken) {

    public boolean configured() {
        return restUrl != null && !restUrl.isBlank() && restToken != null && !restToken.isBlank();
    }
}

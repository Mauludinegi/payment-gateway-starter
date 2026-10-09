package io.github.mauludinegi.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param token bearer token for /api/admin; the web app keeps it in an http-only cookie */
@ConfigurationProperties("admin")
public record AdminProperties(String token) {

    public static final String DEMO_TOKEN = "demo-admin-token";

    public AdminProperties {
        token = token == null || token.isBlank() ? DEMO_TOKEN : token;
    }
}

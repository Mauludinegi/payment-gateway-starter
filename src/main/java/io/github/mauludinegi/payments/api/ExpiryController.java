package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.auth.UnauthorizedException;
import io.github.mauludinegi.payments.service.ExpiryJob;
import io.github.mauludinegi.payments.service.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * For a scheduler outside the app (Vercel Cron sends {@code Authorization: Bearer $CRON_SECRET}).
 * Disabled until CRON_SECRET is set.
 */
@RestController
public class ExpiryController {

    private final ExpiryJob expiry;
    private final byte[] expected;

    public ExpiryController(ExpiryJob expiry, @Value("${payments.cron-secret:}") String secret) {
        this.expiry = expiry;
        this.expected = secret.isBlank() ? null : ("Bearer " + secret).getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/expiry")
    public Map<String, String> run(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (expected == null) {
            throw new NotFoundException("Not found");
        }
        if (authorization == null || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        expiry.run();
        return Map.of("result", "OK");
    }
}

package io.github.mauludinegi.payments.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param firebaseProjectId Firebase project whose Google sign-in ID tokens are accepted; blank disables Google sign-in
 * @param sessionTtl        how long a session lasts after sign-in
 * @param devLoginEnabled   allows signing in with just a name and email, for local runs without Firebase
 * @param upstash           Upstash Redis REST credentials; sessions are kept in memory when blank
 */
@ConfigurationProperties("auth")
public record AuthProperties(
        String firebaseProjectId,
        @DefaultValue("P7D") Duration sessionTtl,
        boolean devLoginEnabled,
        @DefaultValue Upstash upstash) {

    public boolean googleEnabled() {
        return firebaseProjectId != null && !firebaseProjectId.isBlank();
    }

    public record Upstash(String restUrl, String restToken) {

        public boolean configured() {
            return restUrl != null && !restUrl.isBlank() && restToken != null && !restToken.isBlank();
        }
    }
}

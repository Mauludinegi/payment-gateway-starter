package io.github.mauludinegi.payments.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * @param firebaseProjectId Firebase project whose Google sign-in ID tokens are accepted; blank disables Google sign-in
 * @param sessionTtl        how long a session lasts after sign-in
 * @param devLoginEnabled   allows signing in with just a name and email, for local runs without Firebase
 * @param adminEmails       Google accounts (verified email) that become ADMIN when they sign in
 * @param devAdminEmails    dev sign-in emails that become ADMIN; dev sign-in only exists when enabled
 */
@ConfigurationProperties("auth")
public record AuthProperties(
        String firebaseProjectId,
        @DefaultValue("P7D") Duration sessionTtl,
        boolean devLoginEnabled,
        List<String> adminEmails,
        @DefaultValue("admin@example.com") List<String> devAdminEmails) {

    public AuthProperties {
        adminEmails = normalize(adminEmails);
        devAdminEmails = normalize(devAdminEmails);
    }

    private static List<String> normalize(List<String> emails) {
        return emails == null ? List.of()
                : emails.stream().map(e -> e.trim().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty()).toList();
    }

    public boolean googleEnabled() {
        return firebaseProjectId != null && !firebaseProjectId.isBlank();
    }
}

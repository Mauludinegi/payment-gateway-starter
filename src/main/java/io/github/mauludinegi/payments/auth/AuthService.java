package io.github.mauludinegi.payments.auth;

import io.github.mauludinegi.payments.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_NAME = 100;
    private static final int MAX_PICTURE_URL = 1000;

    private final FirebaseTokenVerifier verifier;
    private final UserRepository users;
    private final SessionService sessions;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthService(FirebaseTokenVerifier verifier, UserRepository users, SessionService sessions,
                       AuthProperties properties, Clock clock) {
        this.verifier = verifier;
        this.users = users;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        if (properties.devLoginEnabled()) {
            log.warn("Dev sign-in is enabled: anyone can sign in with any email, and {} become admins. "
                    + "Set AUTH_DEV_LOGIN_ENABLED=false in production.", properties.devAdminEmails());
        }
    }

    public record SignedIn(User user, SessionService.Session session) {
    }

    public SignedIn signInWithGoogle(String idToken) {
        FirebaseTokenVerifier.GoogleUser google = verifier.verify(idToken);
        String email = google.email() != null && google.emailVerified() ? google.email() : null;
        String picture = google.pictureUrl() != null && google.pictureUrl().length() <= MAX_PICTURE_URL ? google.pictureUrl() : null;
        boolean admin = email != null && properties.adminEmails().contains(email.toLowerCase(Locale.ROOT));
        return signIn(google.uid(), email, displayName(google.name(), email), picture, admin);
    }

    /** Local runs without Firebase; never enable in production. */
    public SignedIn signInForDevelopment(String name, String email) {
        if (!properties.devLoginEnabled()) {
            throw new NotFoundException("Dev sign-in is disabled");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return signIn("dev:" + normalized, normalized, displayName(name, normalized), null,
                properties.devAdminEmails().contains(normalized));
    }

    @Transactional(readOnly = true)
    public User user(UUID id) {
        return users.findById(id).orElseThrow(() -> new UnauthorizedException("Session user no longer exists"));
    }

    /**
     * The admin email lists only grant ADMIN; removing an email does not demote anyone, so roles changed in the
     * dashboard stick. The user is saved before the session store is called, so no transaction waits on Redis.
     */
    private SignedIn signIn(String externalId, String email, String name, String pictureUrl, boolean admin) {
        User user = users.findByExternalId(externalId).orElseGet(() -> new User(externalId, clock.instant()));
        user.signedIn(email, name, pictureUrl, clock.instant());
        if (admin && !user.isAdmin()) {
            log.info("Granting ADMIN to {} from the admin email list", email);
            user.changeRole(Role.ADMIN);
        }
        User saved = users.save(user);
        return new SignedIn(saved, sessions.create(saved.getId()));
    }

    private static String displayName(String name, String email) {
        String value = name != null && !name.isBlank() ? name.trim()
                : email != null ? email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length())
                : "Customer";
        return value.length() > MAX_NAME ? value.substring(0, MAX_NAME) : value;
    }
}

package io.github.mauludinegi.payments.auth;

import io.github.mauludinegi.payments.service.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

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
    }

    public record SignedIn(User user, SessionService.Session session) {
    }

    public SignedIn signInWithGoogle(String idToken) {
        FirebaseTokenVerifier.GoogleUser google = verifier.verify(idToken);
        String email = google.email() != null && google.emailVerified() ? google.email() : null;
        String picture = google.pictureUrl() != null && google.pictureUrl().length() <= MAX_PICTURE_URL ? google.pictureUrl() : null;
        return signIn(google.uid(), email, displayName(google.name(), email), picture);
    }

    /** Local runs without Firebase; never enable in production. */
    public SignedIn signInForDevelopment(String name, String email) {
        if (!properties.devLoginEnabled()) {
            throw new NotFoundException("Dev sign-in is disabled");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return signIn("dev:" + normalized, normalized, displayName(name, normalized), null);
    }

    @Transactional(readOnly = true)
    public User user(UUID id) {
        return users.findById(id).orElseThrow(() -> new UnauthorizedException("Session user no longer exists"));
    }

    /** The user is saved before the session store is called, so no transaction waits on Redis. */
    private SignedIn signIn(String externalId, String email, String name, String pictureUrl) {
        User user = users.findByExternalId(externalId).orElseGet(() -> new User(externalId, clock.instant()));
        user.signedIn(email, name, pictureUrl, clock.instant());
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

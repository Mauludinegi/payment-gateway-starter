package io.github.mauludinegi.payments.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);

    private final FirebaseTokenVerifier verifier = mock(FirebaseTokenVerifier.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, User> saved = new HashMap<>();
    private AuthService auth;

    @BeforeEach
    void setUp() {
        when(users.findByExternalId(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get(i.<String>getArgument(0))));
        when(users.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            saved.put(u.getExternalId(), u);
            return u;
        });
        AuthProperties properties = new AuthProperties("demo-shop", Duration.ofDays(7), false,
                List.of(" Owner@Shop.test "), List.of());
        SessionService sessions = new SessionService(new InMemorySessionStore(CLOCK), properties, CLOCK);
        auth = new AuthService(verifier, users, sessions, properties, CLOCK);
    }

    @Test
    void listedGoogleAccountsBecomeAdmins() {
        when(verifier.verify("owner")).thenReturn(new FirebaseTokenVerifier.GoogleUser("uid-1", "owner@shop.test", true, "Owner", null));
        when(verifier.verify("buyer")).thenReturn(new FirebaseTokenVerifier.GoogleUser("uid-2", "buyer@shop.test", true, "Buyer", null));

        assertThat(auth.signInWithGoogle("owner").user().getRole()).isEqualTo(Role.ADMIN);
        assertThat(auth.signInWithGoogle("buyer").user().getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void anUnverifiedEmailIsNotTrusted() {
        when(verifier.verify("t")).thenReturn(new FirebaseTokenVerifier.GoogleUser("uid-3", "owner@shop.test", false, "Fake", null));

        User user = auth.signInWithGoogle("t").user();
        assertThat(user.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void theListNeverDemotes() {
        when(verifier.verify("t")).thenReturn(new FirebaseTokenVerifier.GoogleUser("uid-4", "staff@shop.test", true, "Staff", null));
        auth.signInWithGoogle("t").user().changeRole(Role.ADMIN);

        assertThat(auth.signInWithGoogle("t").user().getRole()).isEqualTo(Role.ADMIN);
    }
}

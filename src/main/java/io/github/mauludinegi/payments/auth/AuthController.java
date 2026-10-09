package io.github.mauludinegi.payments.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;
    private final AuthProperties properties;

    public AuthController(AuthService auth, SessionService sessions, AuthProperties properties) {
        this.auth = auth;
        this.sessions = sessions;
        this.properties = properties;
    }

    public record GoogleSignIn(@NotBlank @Size(max = 4096) String idToken) {
    }

    public record DevSignIn(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 254) String email) {
    }

    public record Options(boolean google, boolean devLogin) {
    }

    public record UserResponse(UUID id, String name, String email, String pictureUrl, Instant createdAt) {

        public static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getPictureUrl(), u.getCreatedAt());
        }
    }

    public record SessionResponse(String token, Instant expiresAt, UserResponse user) {

        static SessionResponse of(AuthService.SignedIn signedIn) {
            return new SessionResponse(signedIn.session().token(), signedIn.session().expiresAt(), UserResponse.of(signedIn.user()));
        }
    }

    /** Which sign-in buttons the web app should show. */
    @GetMapping("/options")
    public Options options() {
        return new Options(properties.googleEnabled(), properties.devLoginEnabled());
    }

    /** Exchanges a Firebase ID token from Google sign-in for a session. */
    @PostMapping("/google")
    public SessionResponse google(@Valid @RequestBody GoogleSignIn body) {
        return SessionResponse.of(auth.signInWithGoogle(body.idToken()));
    }

    @PostMapping("/dev")
    public SessionResponse dev(@Valid @RequestBody DevSignIn body) {
        return SessionResponse.of(auth.signInForDevelopment(body.name(), body.email()));
    }

    @GetMapping("/me")
    public UserResponse me(@RequestAttribute(UserAuth.USER_ID) UUID userId) {
        return UserResponse.of(auth.user(userId));
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest request) {
        sessions.revoke(UserAuth.bearerToken(request));
    }
}

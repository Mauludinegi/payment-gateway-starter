package io.github.mauludinegi.payments.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Set;

/**
 * Verifies Firebase Auth ID tokens the way the Admin SDK does: RS256 against Google's published keys,
 * audience and issuer bound to the project, and not expired. No service account is needed.
 */
@Component
public class FirebaseTokenVerifier {

    static final String KEYS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private final String projectId;
    private final DefaultJWTProcessor<SecurityContext> processor;

    @Autowired
    public FirebaseTokenVerifier(AuthProperties properties) throws MalformedURLException {
        this(properties.firebaseProjectId(), JWKSourceBuilder.<SecurityContext>create(URI.create(KEYS_URL).toURL()).build());
    }

    FirebaseTokenVerifier(String projectId, JWKSource<SecurityContext> keys) {
        this.projectId = projectId;
        this.processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                projectId,
                new JWTClaimsSet.Builder().issuer("https://securetoken.google.com/" + projectId).build(),
                Set.of("sub", "iat", "exp")));
    }

    public record GoogleUser(String uid, String email, boolean emailVerified, String name, String pictureUrl) {
    }

    public GoogleUser verify(String idToken) {
        if (projectId == null || projectId.isBlank()) {
            throw new NotConfiguredException("Google sign-in is not configured; set FIREBASE_PROJECT_ID");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new UnauthorizedException("Invalid Google sign-in token");
        }
        String uid = claims.getSubject();
        if (uid == null || uid.isBlank() || uid.length() > 128) {
            throw new UnauthorizedException("Invalid Google sign-in token");
        }
        try {
            return new GoogleUser(uid, claims.getStringClaim("email"),
                    Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")),
                    claims.getStringClaim("name"), claims.getStringClaim("picture"));
        } catch (ParseException e) {
            throw new UnauthorizedException("Invalid Google sign-in token");
        }
    }

    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }
}

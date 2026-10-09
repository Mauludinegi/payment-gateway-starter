package io.github.mauludinegi.payments.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebaseTokenVerifierTest {

    private static final String PROJECT = "demo-shop";

    static RSAKey googleKey;
    static RSAKey otherKey;
    static FirebaseTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
        verifier = new FirebaseTokenVerifier(PROJECT, new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())));
    }

    @Test
    void acceptsAValidGoogleSignIn() throws Exception {
        var user = verifier.verify(token(googleKey, c -> c));

        assertThat(user.uid()).isEqualTo("firebase-uid-1");
        assertThat(user.email()).isEqualTo("sari@example.com");
        assertThat(user.emailVerified()).isTrue();
        assertThat(user.name()).isEqualTo("Sari Wulandari");
    }

    @Test
    void rejectsTokensForAnotherProject() throws Exception {
        String token = token(googleKey, c -> c.audience("someone-elses-project").issuer("https://securetoken.google.com/someone-elses-project"));
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsExpiredTokens() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(3));
        String token = token(googleKey, c -> c.issueTime(Date.from(past)).expirationTime(Date.from(past.plus(Duration.ofHours(1)))));
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokensNotSignedByGoogle() throws Exception {
        String token = token(otherKey, c -> c);
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> verifier.verify("not.a.jwt")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refusesToVerifyWithoutAProject() {
        var unconfigured = new FirebaseTokenVerifier("", new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())));
        assertThatThrownBy(() -> unconfigured.verify("x")).isInstanceOf(FirebaseTokenVerifier.NotConfiguredException.class);
    }

    private static String token(RSAKey key, UnaryOperator<JWTClaimsSet.Builder> customize) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience(PROJECT)
                .subject("firebase-uid-1")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("auth_time", now.getEpochSecond())
                .claim("email", "sari@example.com")
                .claim("email_verified", true)
                .claim("name", "Sari Wulandari");
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                customize.apply(claims).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}

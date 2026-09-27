package com.me.galchat.utils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED_KEY = Base64.getEncoder().encodeToString(KEY);
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withBean(JwtUtils.class);

    @Test
    void signsWithTheConfiguredKeyAndAcceptsTokensSignedWithThatKey() {
        runner.withPropertyValues("galchat.jwt.signing-key=" + ENCODED_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            JwtUtils jwt = context.getBean(JwtUtils.class);
            String token = jwt.generateToken(Map.of("id", 7));
            var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(KEY)).build()
                    .parseSignedClaims(token).getPayload();
            assertThat(claims.get("id", Integer.class)).isEqualTo(7);
            assertThat(claims.getExpiration()).isAfter(new java.util.Date());
            String externalToken = Jwts.builder().claims(Map.of("id", 8))
                    .signWith(Keys.hmacShaKeyFor(KEY), Jwts.SIG.HS256).compact();
            assertThat(jwt.parseToken(externalToken).get("id", Integer.class)).isEqualTo(8);
        });
    }

    @Test
    void readsTheEnvironmentVariableFallback() {
        runner.withPropertyValues("GALCHAT_JWT_SIGNING_KEY=" + ENCODED_KEY).run(context -> {
            String token = context.getBean(JwtUtils.class).generateToken(Map.of("id", 7));
            assertThat(Jwts.parser().verifyWith(Keys.hmacShaKeyFor(KEY)).build()
                    .parseSignedClaims(token).getPayload().get("id", Integer.class)).isEqualTo(7);
        });
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthKeysAtStartup() {
        for (String value : new String[]{"", "not-base64!", "c2hvcnQ="}) {
            runner.withPropertyValues("galchat.jwt.signing-key=" + value).run(context ->
                    assertThat(context).hasFailed());
        }
    }
}

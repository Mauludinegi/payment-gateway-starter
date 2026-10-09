package io.github.mauludinegi.payments.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    SessionStore sessionStore(AuthProperties properties, RestClient.Builder builder, Clock clock) {
        if (properties.upstash().configured()) {
            log.info("Sessions are stored in Upstash Redis");
            return new UpstashSessionStore(builder, properties.upstash());
        }
        log.warn("UPSTASH_REDIS_REST_URL is not set; sessions are kept in memory and lost on restart");
        return new InMemorySessionStore(clock);
    }
}

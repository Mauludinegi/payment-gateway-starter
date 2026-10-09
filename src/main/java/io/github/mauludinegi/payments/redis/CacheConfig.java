package io.github.mauludinegi.payments.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class CacheConfig {

    @Bean
    KeyValueCache keyValueCache(UpstashProperties upstash, RestClient.Builder builder, Clock clock) {
        return upstash.configured() ? new UpstashCache(new UpstashClient(builder, upstash)) : new InMemoryCache(clock);
    }
}

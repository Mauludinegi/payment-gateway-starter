package io.github.mauludinegi.payments.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstashSessionStoreTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private MockRestServiceServer server;
    private UpstashSessionStore store;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        store = new UpstashSessionStore(builder, new AuthProperties.Upstash("https://redis.upstash.test", "rest-token"));
    }

    @Test
    void savesWithExpiry() {
        server.expect(requestTo("https://redis.upstash.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer rest-token"))
                .andExpect(content().json("[\"SET\",\"session:abc\",\"" + USER + "\",\"EX\",\"3600\"]"))
                .andRespond(withSuccess("{\"result\":\"OK\"}", MediaType.APPLICATION_JSON));

        store.save("session:abc", USER, Duration.ofHours(1));
        server.verify();
    }

    @Test
    void findsAndMissesSessions() {
        server.expect(content().json("[\"GET\",\"session:abc\"]"))
                .andRespond(withSuccess("{\"result\":\"" + USER + "\"}", MediaType.APPLICATION_JSON));
        server.expect(content().json("[\"GET\",\"session:gone\"]"))
                .andRespond(withSuccess("{\"result\":null}", MediaType.APPLICATION_JSON));

        assertThat(store.find("session:abc")).contains(USER);
        assertThat(store.find("session:gone")).isEmpty();
    }

    @Test
    void surfacesRedisErrors() {
        server.expect(content().json("[\"DEL\",\"session:abc\"]"))
                .andRespond(withSuccess("{\"error\":\"WRONGPASS invalid password\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> store.delete("session:abc"))
                .isInstanceOf(UpstashSessionStore.UpstashException.class)
                .hasMessageContaining("WRONGPASS");
    }
}

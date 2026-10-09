package io.github.mauludinegi.payments;

import io.github.mauludinegi.payments.api.OrderEventHub;
import io.github.mauludinegi.payments.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The payment page's live updates: the stream is private to the order's owner and closes once it is settled. */
@SpringBootTest
class OrderEventsTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    OrderEventHub hub;

    MockMvc mvc;
    String owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        owner = "Bearer " + auth.signInForDevelopment("Live Buyer", "live@example.com").session().token();
    }

    @Test
    void streamsEveryChangeUntilTheOrderIsPaid() throws Exception {
        String orderId = createOrder();

        MvcResult stream = mvc.perform(get("/api/orders/{id}/events", orderId).header(HttpHeaders.AUTHORIZATION, owner)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        MockHttpServletResponse response = stream.getResponse();
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        awaitContent(response, c -> c.contains("event:order") && c.contains("\"status\":\"PENDING_PAYMENT\""));

        mvc.perform(post("/api/orders/{id}/payments", orderId).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BRI_VA\"}"))
                .andExpect(status().isCreated());
        awaitContent(response, c -> c.contains("\"channel\":\"BRI_VA\""));

        mvc.perform(post("/api/simulator/orders/{id}/pay", orderId)).andExpect(status().isOk());
        awaitContent(response, c -> c.contains("\"status\":\"PAID\""));
    }

    @Test
    void onlyTheOwnerCanListen() throws Exception {
        String orderId = createOrder();
        String stranger = "Bearer " + auth.signInForDevelopment("Stranger", "stranger@example.com").session().token();

        mvc.perform(get("/api/orders/{id}/events", orderId).header(HttpHeaders.AUTHORIZATION, stranger)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}/events", orderId).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shutdownClosesOpenStreams() throws Exception {
        String orderId = createOrder();
        MvcResult stream = mvc.perform(get("/api/orders/{id}/events", orderId).header(HttpHeaders.AUTHORIZATION, owner)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        hub.closeAll();

        stream.getAsyncResult(2_000);
    }

    private String createOrder() throws Exception {
        String body = mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":\"ebook-api\",\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asString();
    }

    private static void awaitContent(MockHttpServletResponse response, Predicate<String> condition) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (condition.test(response.getContentAsString())) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(response.getContentAsString()).matches(condition);
    }
}

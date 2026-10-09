package io.github.mauludinegi.payments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AuthApiTest {

    private static final String ORDER = "{\"items\":[{\"productId\":\"ebook-api\",\"quantity\":1}]}";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void checkoutAndAccountNeedASession() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isOk());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordersBelongToTheSignedInCustomer() throws Exception {
        String sari = signIn("Sari Wulandari", "Sari@Example.com");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, sari))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sari Wulandari"))
                .andExpect(jsonPath("$.email").value("sari@example.com"));

        String response = mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, sari)
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Sari Wulandari"))
                .andExpect(jsonPath("$.customerEmail").value("sari@example.com"))
                .andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(response).path("id").asString();

        mvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, sari))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(orderId))
                .andExpect(jsonPath("$[0].items[0].productId").value("ebook-api"));

        String other = signIn("Andi", "andi@example.com");
        mvc.perform(get("/api/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/{id}/payments", orderId).header(HttpHeaders.AUTHORIZATION, other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void signingInAgainKeepsTheSameAccount() throws Exception {
        String first = signIn("Rudi", "rudi@example.com");
        String second = signIn("Rudi Hartono", "RUDI@example.com");
        String firstId = json.readTree(me(first)).path("id").asString();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, second))
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.name").value("Rudi Hartono"));
    }

    @Test
    void signOutRevokesTheSession() throws Exception {
        String session = signIn("Lina", "lina@example.com");
        mvc.perform(delete("/api/auth/session").header(HttpHeaders.AUTHORIZATION, session))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void googleSignInNeedsAFirebaseProject() throws Exception {
        mvc.perform(get("/api/auth/options"))
                .andExpect(jsonPath("$.google").value(false))
                .andExpect(jsonPath("$.devLogin").value(true));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"x\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    private String signIn(String name, String email) throws Exception {
        String response = mvc.perform(post("/api/auth/dev").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("token").asString();
    }

    private String me(String session) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, session))
                .andReturn().getResponse().getContentAsString();
    }
}

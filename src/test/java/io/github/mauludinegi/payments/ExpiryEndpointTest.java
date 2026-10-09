package io.github.mauludinegi.payments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The expiry job can be started from outside (Vercel Cron), but only with the cron secret. */
@SpringBootTest(properties = "payments.cron-secret=test-cron-secret")
class ExpiryEndpointTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void runsWithTheCronSecret() throws Exception {
        mvc.perform(get("/internal/expiry").header(HttpHeaders.AUTHORIZATION, "Bearer test-cron-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"));
    }

    @Test
    void rejectsAMissingOrWrongSecret() throws Exception {
        mvc.perform(get("/internal/expiry")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/expiry").header(HttpHeaders.AUTHORIZATION, "Bearer guess"))
                .andExpect(status().isUnauthorized());
    }
}

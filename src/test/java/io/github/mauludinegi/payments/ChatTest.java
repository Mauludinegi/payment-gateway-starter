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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ChatTest {

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
    void chatRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/chat/room"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/chat/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCanChatWithAdmin() throws Exception {
        String customerAuth = signIn("Budi Santoso", "budi@chat.test");
        String adminAuth = signIn("Admin User", "admin@example.com");

        // 1. Customer accesses their chat room
        String roomResponse = mvc.perform(get("/api/chat/room")
                        .header(HttpHeaders.AUTHORIZATION, customerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.customerName").value("Budi Santoso"))
                .andReturn().getResponse().getContentAsString();

        String roomId = json.readTree(roomResponse).get("id").asText();

        // 2. Customer sends a message
        mvc.perform(post("/api/chat/rooms/" + roomId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, customerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Halo min, mau tanya metode bayar QRIS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderRole").value("CUSTOMER"))
                .andExpect(jsonPath("$.text").value("Halo min, mau tanya metode bayar QRIS"));

        // 3. Admin views rooms and sees customer's room
        mvc.perform(get("/api/admin/chat/rooms")
                        .header(HttpHeaders.AUTHORIZATION, adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + roomId + "')].lastMessageText")
                        .value("Halo min, mau tanya metode bayar QRIS"));

        // 4. Admin replies to customer
        mvc.perform(post("/api/admin/chat/rooms/" + roomId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Halo Budi, silakan scan barcode QRIS yang muncul."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderRole").value("ADMIN"))
                .andExpect(jsonPath("$.text").value("Halo Budi, silakan scan barcode QRIS yang muncul."));

        // 5. Customer checks message history
        mvc.perform(get("/api/chat/rooms/" + roomId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, customerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].senderRole").value("CUSTOMER"))
                .andExpect(jsonPath("$[1].senderRole").value("ADMIN"));

        // 6. Register FCM token
        mvc.perform(post("/api/chat/fcm-token")
                        .header(HttpHeaders.AUTHORIZATION, customerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", "fake-fcm-token-12345", "deviceInfo", "Chrome on macOS"))))
                .andExpect(status().isOk());
    }

    private String signIn(String name, String email) throws Exception {
        String res = mvc.perform(post("/api/auth/dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(res).get("token").asText();
    }
}

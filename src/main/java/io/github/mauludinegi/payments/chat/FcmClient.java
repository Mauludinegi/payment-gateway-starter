package io.github.mauludinegi.payments.chat;

import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final RestClient restClient;
    private final String credentialsPath;
    private GoogleCredentials credentials;
    private String projectId;
    private boolean configured;

    public FcmClient(
            RestClient.Builder restClientBuilder,
            @Value("${chat.fcm.credentials-path:firebase-service-account.json}") String credentialsPath
    ) {
        this.restClient = restClientBuilder.build();
        this.credentialsPath = credentialsPath;
        initCredentials();
    }

    private void initCredentials() {
        byte[] bytes = null;
        String b64 = System.getenv("FCM_CREDENTIALS_BASE64");
        String rawJson = System.getenv("FCM_CREDENTIALS_JSON");

        if (b64 != null && !b64.isBlank()) {
            try {
                bytes = java.util.Base64.getDecoder().decode(b64.trim());
            } catch (Exception e) {
                log.warn("Failed to decode FCM_CREDENTIALS_BASE64: {}", e.getMessage());
            }
        } else if (rawJson != null && !rawJson.isBlank()) {
            bytes = rawJson.trim().getBytes(StandardCharsets.UTF_8);
        } else {
            File file = new File(credentialsPath);
            if (!file.exists()) {
                File renderSecret = new File("/etc/secrets/firebase-service-account.json");
                if (renderSecret.exists()) {
                    file = renderSecret;
                }
            }
            if (file.exists()) {
                try (InputStream in = new FileInputStream(file)) {
                    bytes = in.readAllBytes();
                } catch (Exception e) {
                    log.warn("Failed reading FCM credentials file: {}", e.getMessage());
                }
            }
        }

        if (bytes == null || bytes.length == 0) {
            log.info("FCM credentials file not found at '{}'; push notifications disabled", credentialsPath);
            this.configured = false;
            return;
        }

        try {
            JsonParser parser = JsonParserFactory.getJsonParser();
            Map<String, Object> json = parser.parseMap(new String(bytes, StandardCharsets.UTF_8));
            this.projectId = (String) json.get("project_id");

            this.credentials = GoogleCredentials.fromStream(new java.io.ByteArrayInputStream(bytes))
                    .createScoped(List.of(FCM_SCOPE));
            this.configured = true;
            log.info("FCM push notifications configured for project: {}", projectId);
        } catch (Exception e) {
            log.warn("Failed to initialize FCM credentials: {}", e.getMessage());
            this.configured = false;
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean sendPush(String token, String title, String body, Map<String, String> data) {
        if (!configured || token == null || token.isBlank()) {
            return false;
        }

        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();

            String url = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";

            Map<String, Object> payload = Map.of(
                    "message", Map.of(
                            "token", token,
                            "notification", Map.of(
                                    "title", title,
                                    "body", body
                            ),
                            "data", data
                    )
            );

            restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully sent FCM push to token: {}...", token.substring(0, Math.min(token.length(), 10)));
            return true;
        } catch (Exception e) {
            log.warn("Failed to send FCM push notification: {}", e.getMessage());
            return false;
        }
    }
}

package io.github.mauludinegi.payments.chat;

import io.github.mauludinegi.payments.auth.Role;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ChatFcmWorker {

    private static final Logger log = LoggerFactory.getLogger(ChatFcmWorker.class);

    private final FcmClient fcmClient;
    private final UserFcmTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public ChatFcmWorker(
            FcmClient fcmClient,
            UserFcmTokenRepository tokenRepository,
            UserRepository userRepository
    ) {
        this.fcmClient = fcmClient;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
    }

    public void sendPushForMessage(ChatMessageEvent event) {
        if (!fcmClient.isConfigured()) {
            return;
        }

        List<UUID> recipientUserIds = new ArrayList<>();
        if ("CUSTOMER".equalsIgnoreCase(event.senderRole())) {
            // Notify all admins
            List<User> admins = userRepository.findByRole(Role.ADMIN);
            for (User admin : admins) {
                recipientUserIds.add(admin.getId());
            }
        } else {
            // Admin replied, notify the customer
            recipientUserIds.add(event.customerId());
        }

        String title = "Pesan baru dari " + event.senderName();
        String body = event.text().length() > 80 ? event.text().substring(0, 77) + "..." : event.text();
        Map<String, String> data = Map.of(
                "roomId", event.roomId().toString(),
                "messageId", event.messageId().toString(),
                "senderRole", event.senderRole()
        );

        for (UUID userId : recipientUserIds) {
            // Don't send push to sender themselves
            if (userId.equals(event.senderId())) {
                continue;
            }
            List<UserFcmToken> tokens = tokenRepository.findByUserId(userId);
            for (UserFcmToken token : tokens) {
                boolean sent = fcmClient.sendPush(token.getToken(), title, body, data);
                if (!sent) {
                    log.info("Removing stale/invalid FCM token for user {}: {}...", userId, token.getToken().substring(0, Math.min(token.getToken().length(), 10)));
                    tokenRepository.delete(token);
                }
            }
        }
    }
}

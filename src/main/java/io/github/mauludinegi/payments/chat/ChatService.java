package io.github.mauludinegi.payments.chat;

import io.github.mauludinegi.payments.auth.ForbiddenException;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ChatService {

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final UserFcmTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ChatEventPublisher publisher;

    public ChatService(
            ChatRoomRepository roomRepository,
            ChatMessageRepository messageRepository,
            UserFcmTokenRepository tokenRepository,
            UserRepository userRepository,
            ChatEventPublisher publisher
    ) {
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.publisher = publisher;
    }

    public record RoomSummary(
            UUID id,
            UUID customerId,
            String customerName,
            String customerEmail,
            String customerPictureUrl,
            String status,
            Instant createdAt,
            Instant lastMessageAt,
            String lastMessageText
    ) {}

    public record MessageDto(
            UUID id,
            UUID roomId,
            UUID senderId,
            String senderRole,
            String senderName,
            String text,
            Instant createdAt
    ) {
        public static MessageDto of(ChatMessage m) {
            return new MessageDto(
                    m.getId(),
                    m.getRoomId(),
                    m.getSenderId(),
                    m.getSenderRole(),
                    m.getSenderName(),
                    m.getText(),
                    m.getCreatedAt()
            );
        }
    }

    public ChatRoom getOrCreateRoom(UUID customerId) {
        return roomRepository.findByCustomerIdAndStatus(customerId, "OPEN")
                .orElseGet(() -> roomRepository.save(new ChatRoom(customerId, Instant.now())));
    }

    @Transactional(readOnly = true)
    public RoomSummary getRoomSummary(UUID roomId) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
        return toSummary(room);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> listRoomsForAdmin() {
        List<ChatRoom> rooms = roomRepository.findAllByOrderByLastMessageAtDesc();
        List<RoomSummary> summaries = new ArrayList<>();
        for (ChatRoom room : rooms) {
            summaries.add(toSummary(room));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(UUID roomId, UUID userId, boolean isAdmin) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
        if (!isAdmin && !room.getCustomerId().equals(userId)) {
            throw new ForbiddenException("Cannot access other customer's chat");
        }

        return messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId).stream()
                .map(MessageDto::of)
                .toList();
    }

    public MessageDto sendMessage(UUID roomId, UUID senderId, boolean isAdmin, String text) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
        if (!isAdmin && !room.getCustomerId().equals(senderId)) {
            throw new ForbiddenException("Cannot send message to this chat room");
        }
        if (!isAdmin && "CLOSED".equalsIgnoreCase(room.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Percakapan ini telah ditutup / dinonaktifkan oleh admin.");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Instant now = Instant.now();
        String role = isAdmin ? "ADMIN" : "CUSTOMER";
        ChatMessage msg = new ChatMessage(roomId, senderId, role, sender.getName(), text.trim(), now);
        msg = messageRepository.save(msg);

        room.updateLastMessageAt(now);
        roomRepository.save(room);

        ChatMessageEvent event = ChatMessageEvent.of(msg, room.getCustomerId());
        publisher.publish(event);

        return MessageDto.of(msg);
    }

    public RoomSummary updateRoomStatus(UUID roomId, String status) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
        room.setStatus(status.toUpperCase());
        roomRepository.save(room);
        return toSummary(room);
    }

    public void deleteRoom(UUID roomId) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
        roomRepository.delete(room);
    }

    public void registerFcmToken(UUID userId, String token, String deviceInfo) {
        if (token == null || token.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Optional<UserFcmToken> existing = tokenRepository.findByToken(token);
        if (existing.isPresent()) {
            UserFcmToken t = existing.get();
            t.update(userId, deviceInfo, now);
            tokenRepository.save(t);
        } else {
            tokenRepository.save(new UserFcmToken(userId, token, deviceInfo, now));
        }
    }

    private RoomSummary toSummary(ChatRoom room) {
        User customer = userRepository.findById(room.getCustomerId()).orElse(null);
        List<ChatMessage> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(room.getId());
        String lastText = messages.isEmpty() ? null : messages.get(messages.size() - 1).getText();

        return new RoomSummary(
                room.getId(),
                room.getCustomerId(),
                customer != null ? customer.getName() : "Customer",
                customer != null ? customer.getEmail() : null,
                customer != null ? customer.getPictureUrl() : null,
                room.getStatus(),
                room.getCreatedAt(),
                room.getLastMessageAt(),
                lastText
        );
    }
}

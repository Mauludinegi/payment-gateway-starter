package io.github.mauludinegi.payments.chat;

import io.github.mauludinegi.payments.auth.UserAuth;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
public class ChatController {

    private final ChatService chatService;
    private final ChatEventHub chatEventHub;

    public ChatController(ChatService chatService, ChatEventHub chatEventHub) {
        this.chatService = chatService;
        this.chatEventHub = chatEventHub;
    }

    public record SendMessageRequest(@NotBlank @Size(max = 2000) String text) {}
    public record FcmTokenRequest(@NotBlank String token, String deviceInfo) {}
    public record UpdateStatusRequest(@NotBlank String status) {}

    /* ----------------------------------------------------
     * Customer Endpoints (requires session via UserAuth)
     * ---------------------------------------------------- */

    @GetMapping("/api/chat/room")
    public ChatService.RoomSummary myRoom(@RequestAttribute(UserAuth.USER_ID) UUID userId) {
        ChatRoom room = chatService.getOrCreateRoom(userId);
        return chatService.getRoomSummary(room.getId());
    }

    @GetMapping("/api/chat/rooms/{roomId}/messages")
    public List<ChatService.MessageDto> customerMessages(
            @RequestAttribute(UserAuth.USER_ID) UUID userId,
            @PathVariable UUID roomId
    ) {
        return chatService.listMessages(roomId, userId, false);
    }

    @PostMapping("/api/chat/rooms/{roomId}/messages")
    public ChatService.MessageDto customerSendMessage(
            @RequestAttribute(UserAuth.USER_ID) UUID userId,
            @PathVariable UUID roomId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return chatService.sendMessage(roomId, userId, false, request.text());
    }

    @GetMapping(path = "/api/chat/rooms/{roomId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter customerEvents(
            @RequestAttribute(UserAuth.USER_ID) UUID userId,
            @PathVariable UUID roomId,
            HttpServletResponse response
    ) {
        // Validate access before subscribing
        chatService.listMessages(roomId, userId, false);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return chatEventHub.subscribe(roomId);
    }

    @PostMapping("/api/chat/fcm-token")
    public void registerToken(
            @RequestAttribute(UserAuth.USER_ID) UUID userId,
            @Valid @RequestBody FcmTokenRequest request
    ) {
        chatService.registerFcmToken(userId, request.token(), request.deviceInfo());
    }

    /* ----------------------------------------------------
     * Admin Endpoints (requires admin session via AdminAuth)
     * ---------------------------------------------------- */

    @GetMapping("/api/admin/chat/rooms")
    public List<ChatService.RoomSummary> adminRooms() {
        return chatService.listRoomsForAdmin();
    }

    @GetMapping("/api/admin/chat/rooms/{roomId}/messages")
    public List<ChatService.MessageDto> adminMessages(
            @RequestAttribute(UserAuth.USER_ID) UUID adminId,
            @PathVariable UUID roomId
    ) {
        return chatService.listMessages(roomId, adminId, true);
    }

    @PostMapping("/api/admin/chat/rooms/{roomId}/messages")
    public ChatService.MessageDto adminSendMessage(
            @RequestAttribute(UserAuth.USER_ID) UUID adminId,
            @PathVariable UUID roomId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return chatService.sendMessage(roomId, adminId, true, request.text());
    }

    @GetMapping(path = "/api/admin/chat/rooms/{roomId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter adminEvents(
            @RequestAttribute(UserAuth.USER_ID) UUID adminId,
            @PathVariable UUID roomId,
            HttpServletResponse response
    ) {
        // Validate room exists
        chatService.getRoomSummary(roomId);
        return chatEventHub.subscribe(roomId);
    }

    @PatchMapping("/api/admin/chat/rooms/{roomId}/status")
    public ChatService.RoomSummary updateRoomStatus(
            @PathVariable UUID roomId,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        return chatService.updateRoomStatus(roomId, request.status());
    }

    @DeleteMapping("/api/admin/chat/rooms/{roomId}")
    public void deleteRoom(@PathVariable UUID roomId) {
        chatService.deleteRoom(roomId);
    }
}

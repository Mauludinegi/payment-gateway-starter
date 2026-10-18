package io.github.mauludinegi.payments.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Server-Sent Events (SSE) connections for live chat rooms.
 */
@Component
public class ChatEventHub {

    private static final Logger log = LoggerFactory.getLogger(ChatEventHub.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private static final int MAX_STREAMS_PER_ROOM = 20;

    private final Map<UUID, Set<SseEmitter>> roomStreams = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID roomId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Set<SseEmitter> forRoom = roomStreams.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet());
        if (forRoom.size() >= MAX_STREAMS_PER_ROOM) {
            emitter.complete();
            return emitter;
        }

        forRoom.add(emitter);
        Runnable remove = () -> remove(roomId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove(roomId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("roomId", roomId)));
        } catch (IOException e) {
            emitter.complete();
            remove(roomId, emitter);
        }

        return emitter;
    }

    public void broadcast(ChatMessageEvent event) {
        Set<SseEmitter> emitters = roomStreams.get(event.roomId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("chat").data(event));
            } catch (Exception e) {
                emitter.complete();
                remove(event.roomId(), emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 25_000)
    void ping() {
        for (Map.Entry<UUID, Set<SseEmitter>> entry : roomStreams.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException e) {
                    emitter.complete();
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(UUID roomId, SseEmitter emitter) {
        Set<SseEmitter> forRoom = roomStreams.get(roomId);
        if (forRoom != null) {
            forRoom.remove(emitter);
            if (forRoom.isEmpty()) {
                roomStreams.remove(roomId, forRoom);
            }
        }
    }
}

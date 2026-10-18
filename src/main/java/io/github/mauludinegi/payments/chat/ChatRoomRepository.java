package io.github.mauludinegi.payments.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, UUID> {

    Optional<ChatRoom> findByCustomerIdAndStatus(UUID customerId, String status);

    List<ChatRoom> findAllByOrderByLastMessageAtDesc();
}

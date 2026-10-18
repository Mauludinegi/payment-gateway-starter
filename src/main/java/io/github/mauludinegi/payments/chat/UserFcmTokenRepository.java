package io.github.mauludinegi.payments.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserFcmTokenRepository extends JpaRepository<UserFcmToken, UUID> {

    List<UserFcmToken> findByUserId(UUID userId);

    Optional<UserFcmToken> findByToken(String token);
}

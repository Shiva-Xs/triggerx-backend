package com.triggerx.telegram;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TelegramUserRepository extends JpaRepository<TelegramUser, UUID> {

    @Query("SELECT tu FROM TelegramUser tu JOIN FETCH tu.user WHERE tu.chatId = :chatId")
    Optional<TelegramUser> findByChatIdWithUser(@Param("chatId") Long chatId);

    Optional<TelegramUser> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}

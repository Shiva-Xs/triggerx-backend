package com.triggerx.telegram;

import com.triggerx.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "telegram_users")
@Getter
@Setter
@NoArgsConstructor
public class TelegramUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private Long chatId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private LocalDateTime linkedAt;

    @PrePersist
    protected void onCreate() {
        this.linkedAt = LocalDateTime.now();
    }

    public TelegramUser(Long chatId, User user) {
        this.chatId = chatId;
        this.user = user;
    }
}

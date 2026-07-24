package com.github.taskmasterbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "telegram_users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_telegram_users_telegram_user_id",
                columnNames = "telegram_user_id"
        ),
        indexes = @Index(name = "idx_telegram_users_chat_id", columnList = "chat_id")
)
public class TelegramUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @NotNull
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Size(max = 255)
    @Column(name = "username", length = 255)
    private String username;

    @Size(max = 255)
    @Column(name = "first_name", length = 255)
    private String firstName;

    @Size(max = 255)
    @Column(name = "last_name", length = 255)
    private String lastName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TelegramUser() {
    }

    public TelegramUser(
            Long telegramUserId,
            Long chatId,
            String username,
            String firstName,
            String lastName
    ) {
        this.telegramUserId = telegramUserId;
        updateProfile(chatId, username, firstName, lastName);
    }

    public void updateProfile(
            Long chatId,
            String username,
            String firstName,
            String lastName
    ) {
        this.chatId = chatId;
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public Long getId() {
        return id;
    }

    public Long getTelegramUserId() {
        return telegramUserId;
    }

    public Long getChatId() {
        return chatId;
    }

    public String getUsername() {
        return username;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.github.taskmasterbot.dto;

public record TelegramUserData(
        Long telegramUserId,
        Long chatId,
        String username,
        String firstName,
        String lastName
) {
}

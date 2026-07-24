package com.github.taskmasterbot.dto;

import com.github.taskmasterbot.entity.TaskPriority;

import java.time.Instant;

public record CreateTaskCommand(
        TelegramUserData user,
        String title,
        String description,
        TaskPriority priority,
        Instant deadline
) {
}

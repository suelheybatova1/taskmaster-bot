package com.github.taskmasterbot.dto;

import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;

import java.time.Instant;

public record TaskDetails(
        Long id,
        String title,
        String description,
        TaskPriority priority,
        TaskStatus status,
        Instant deadline,
        Instant createdAt,
        Instant completedAt
) {
}

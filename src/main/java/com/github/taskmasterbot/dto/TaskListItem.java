package com.github.taskmasterbot.dto;

import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;

import java.time.Instant;

public record TaskListItem(
        Long id,
        String title,
        TaskPriority priority,
        TaskStatus status,
        Instant deadline
) {
}

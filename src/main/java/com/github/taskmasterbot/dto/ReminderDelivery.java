package com.github.taskmasterbot.dto;

import com.github.taskmasterbot.entity.ReminderType;
import com.github.taskmasterbot.entity.TaskPriority;

import java.time.Instant;

public record ReminderDelivery(
        long reminderId,
        long chatId,
        String title,
        TaskPriority priority,
        Instant deadline,
        ReminderType reminderType
) {
}

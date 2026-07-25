package com.github.taskmasterbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@ConfigurationProperties(prefix = "taskmaster.reminders")
@Validated
public record ReminderProperties(
        boolean enabled,
        @Min(1)
        long pollInterval,
        @Min(1)
        int batchSize,
        @Min(1)
        int maxAttempts
) {
}

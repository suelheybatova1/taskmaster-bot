package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.dto.TaskDetails;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Component
public class TaskDetailsFormatter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private final ApplicationProperties properties;

    public TaskDetailsFormatter(ApplicationProperties properties) {
        this.properties = properties;
    }

    public String format(TaskDetails task) {
        return """
                📝 Task #%d

                Title: %s
                Description: %s
                Priority: %s
                Status: %s
                Deadline: %s
                Created: %s"""
                .formatted(
                        task.id(),
                        task.title(),
                        task.description() == null ? "No description" : task.description(),
                        priority(task.priority()),
                        status(task.status()),
                        dateTime(task.deadline(), "No deadline"),
                        dateTime(task.createdAt(), "Unknown")
                );
    }

    private String priority(TaskPriority priority) {
        return switch (priority) {
            case LOW -> "🟢 Low";
            case MEDIUM -> "🟡 Medium";
            case HIGH -> "🔴 High";
        };
    }

    private String status(TaskStatus status) {
        return switch (status) {
            case TODO -> "📝 Todo";
            case IN_PROGRESS -> "🚧 In progress";
            case COMPLETED -> "✅ Completed";
        };
    }

    private String dateTime(Instant instant, String absentValue) {
        return instant == null
                ? absentValue
                : DATE_TIME_FORMATTER.format(instant.atZone(properties.timeZone()));
    }
}

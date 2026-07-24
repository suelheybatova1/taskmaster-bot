package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.dto.TaskListItem;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
public class TaskListFormatter {

    public static final String EMPTY_TASKS_MESSAGE = "📭 You have no active tasks.";

    private static final DateTimeFormatter DEADLINE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private final ApplicationProperties properties;

    public TaskListFormatter(ApplicationProperties properties) {
        this.properties = properties;
    }

    public String format(TaskPage page) {
        if (page.tasks().isEmpty()) {
            return EMPTY_TASKS_MESSAGE;
        }

        return page.tasks().stream()
                .map(this::formatTask)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatTask(TaskListItem task) {
        String deadline = task.deadline() == null
                ? "No deadline"
                : DEADLINE_FORMATTER.format(task.deadline().atZone(properties.timeZone()));

        return """
                #%d — %s
                Priority: %s
                Status: %s
                Deadline: %s"""
                .formatted(
                        task.id(),
                        task.title(),
                        formatPriority(task.priority()),
                        formatStatus(task.status()),
                        deadline
                );
    }

    private String formatPriority(TaskPriority priority) {
        return switch (priority) {
            case LOW -> "🟢 Low";
            case MEDIUM -> "🟡 Medium";
            case HIGH -> "🔴 High";
        };
    }

    private String formatStatus(TaskStatus status) {
        return switch (status) {
            case TODO -> "📝 Todo";
            case IN_PROGRESS -> "🚧 In progress";
            case COMPLETED -> "✅ Completed";
        };
    }
}

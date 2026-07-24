package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskDeletionCallback {

    private static final Pattern PATTERN =
            Pattern.compile("^task:(delete|delete-confirm|delete-cancel):([1-9]\\d{0,18})$");

    public Optional<ParsedCallback> parse(String callbackData) {
        if (callbackData == null) {
            return Optional.empty();
        }

        Matcher matcher = PATTERN.matcher(callbackData);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            Action action = switch (matcher.group(1)) {
                case "delete" -> Action.REQUEST;
                case "delete-confirm" -> Action.CONFIRM;
                case "delete-cancel" -> Action.CANCEL;
                default -> throw new IllegalStateException("Unexpected deletion action");
            };
            return Optional.of(new ParsedCallback(action, Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public String request(long taskId) {
        return "task:delete:" + positiveTaskId(taskId);
    }

    public String confirm(long taskId) {
        return "task:delete-confirm:" + positiveTaskId(taskId);
    }

    public String cancel(long taskId) {
        return "task:delete-cancel:" + positiveTaskId(taskId);
    }

    private long positiveTaskId(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("Task ID must be positive");
        }
        return taskId;
    }

    public enum Action {
        REQUEST,
        CONFIRM,
        CANCEL
    }

    public record ParsedCallback(Action action, long taskId) {
    }
}

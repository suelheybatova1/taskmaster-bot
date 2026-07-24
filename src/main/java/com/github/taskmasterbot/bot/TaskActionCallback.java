package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskActionCallback {

    private static final Pattern PATTERN =
            Pattern.compile("^task:(view|start|complete):([1-9]\\d{0,18})$");

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
                case "view" -> Action.VIEW;
                case "start" -> Action.START;
                case "complete" -> Action.COMPLETE;
                default -> throw new IllegalStateException("Unexpected task action");
            };
            return Optional.of(new ParsedCallback(action, Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public String view(long taskId) {
        return callback("view", taskId);
    }

    public String start(long taskId) {
        return callback("start", taskId);
    }

    public String complete(long taskId) {
        return callback("complete", taskId);
    }

    private String callback(String action, long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("Task ID must be positive");
        }
        return "task:" + action + ":" + taskId;
    }

    public enum Action {
        VIEW,
        START,
        COMPLETE
    }

    public record ParsedCallback(Action action, long taskId) {
    }
}

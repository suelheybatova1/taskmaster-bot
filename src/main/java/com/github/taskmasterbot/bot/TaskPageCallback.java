package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskPageCallback {

    private static final Pattern PATTERN = Pattern.compile("^tasks:page:(\\d{1,6})$");

    public OptionalInt parse(String callbackData) {
        if (callbackData == null) {
            return OptionalInt.empty();
        }

        Matcher matcher = PATTERN.matcher(callbackData);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    public String create(int pageNumber) {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number must not be negative");
        }
        return "tasks:page:" + pageNumber;
    }
}

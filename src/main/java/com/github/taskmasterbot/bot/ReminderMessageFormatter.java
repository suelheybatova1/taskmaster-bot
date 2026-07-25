package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.dto.ReminderDelivery;
import com.github.taskmasterbot.entity.ReminderType;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class ReminderMessageFormatter {
    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ApplicationProperties properties;

    public ReminderMessageFormatter(ApplicationProperties properties) {
        this.properties = properties;
    }

    public String format(ReminderDelivery delivery) {
        String deadline = DEADLINE_FORMAT.withZone(properties.timeZone())
                .format(delivery.deadline());
        if (delivery.reminderType() == ReminderType.DEADLINE_REACHED) {
            return "⚠️ Task deadline reached"
                    + "\n\n📝 " + delivery.title()
                    + "\n📅 " + deadline;
        }
        String remaining = delivery.reminderType()
                == ReminderType.DEADLINE_24_HOURS
                ? "24 hours" : "1 hour";
        return "⏰ Task reminder"
                + "\n\n📝 " + delivery.title()
                + "\n" + priority(delivery)
                + "\n📅 Deadline in " + remaining
                + "\n🕒 " + deadline;
    }

    private String priority(ReminderDelivery delivery) {
        return switch (delivery.priority()) {
            case LOW -> "🟢 Low";
            case MEDIUM -> "🟡 Medium";
            case HIGH -> "🔴 High";
        };
    }
}

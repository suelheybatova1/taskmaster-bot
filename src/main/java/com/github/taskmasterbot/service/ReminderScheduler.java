package com.github.taskmasterbot.service;

import com.github.taskmasterbot.bot.TelegramNotificationSender;
import com.github.taskmasterbot.dto.ReminderDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "taskmaster.reminders", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private final ReminderService reminderService;
    private final TelegramNotificationSender sender;

    public ReminderScheduler(ReminderService reminderService, TelegramNotificationSender sender) {
        this.reminderService = reminderService;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${taskmaster.reminders.poll-interval:60000}")
    public void processDueReminders() {
        for (ReminderDelivery delivery : reminderService.claimDueReminders()) {
            try {
                sender.send(delivery);
                reminderService.markSent(delivery.reminderId());
            } catch (RuntimeException exception) {
                log.warn("Reminder delivery failed: reminderId={}, errorType={}",
                        delivery.reminderId(), exception.getClass().getSimpleName());
                reminderService.markFailed(
                        delivery.reminderId(), exception.getClass().getSimpleName());
            }
        }
    }
}

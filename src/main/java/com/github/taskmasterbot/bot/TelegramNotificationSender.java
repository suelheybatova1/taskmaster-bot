package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.dto.ReminderDelivery;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
public class TelegramNotificationSender {
    private final TelegramClient telegramClient;
    private final ReminderMessageFormatter formatter;

    public TelegramNotificationSender(
            TelegramClient telegramClient, ReminderMessageFormatter formatter) {
        this.telegramClient = telegramClient;
        this.formatter = formatter;
    }

    public void send(ReminderDelivery delivery) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(delivery.chatId())
                    .text(formatter.format(delivery))
                    .build());
        } catch (TelegramApiException exception) {
            throw new NotificationDeliveryException(exception);
        }
    }

    public static final class NotificationDeliveryException extends RuntimeException {
        private NotificationDeliveryException(Throwable cause) {
            super("Telegram reminder delivery failed", cause);
        }
    }
}

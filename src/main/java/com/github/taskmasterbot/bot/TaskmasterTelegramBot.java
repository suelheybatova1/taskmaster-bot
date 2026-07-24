package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
public class TaskmasterTelegramBot
        implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    static final String START_RESPONSE = """
            👋 Welcome to TaskMaster Bot!

            This bot will help you manage your daily tasks.""";

    static final String HELP_RESPONSE = """
            Available commands:

            /start
            /help""";

    static final String DEFAULT_RESPONSE = "TaskMaster Bot is connected.";

    private static final Logger log = LoggerFactory.getLogger(TaskmasterTelegramBot.class);

    private final TelegramProperties properties;
    private final TelegramClient telegramClient;

    public TaskmasterTelegramBot(
            TelegramProperties properties,
            TelegramClient telegramClient
    ) {
        this.properties = properties;
        this.telegramClient = telegramClient;
    }

    @Override
    public String getBotToken() {
        return properties.token();
    }

    public String getBotUsername() {
        return properties.username();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        String receivedText = message.getText();
        Long userId = message.getFrom() == null ? null : message.getFrom().getId();
        Long chatId = message.getChatId();

        log.info(
                "Received Telegram message: userId={}, chatId={}, text={}",
                userId,
                chatId,
                receivedText
        );

        String responseText = switch (receivedText.trim()) {
            case "/start" -> START_RESPONSE;
            case "/help" -> HELP_RESPONSE;
            default -> DEFAULT_RESPONSE;
        };

        sendMessage(chatId, responseText);
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage response = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();

        try {
            telegramClient.execute(response);
        } catch (TelegramApiException exception) {
            log.error(
                    "Failed to send Telegram message: chatId={}, errorType={}",
                    chatId,
                    exception.getClass().getSimpleName()
            );
        }
    }
}

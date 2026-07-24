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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
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
            /help
            /cancel""";

    static final String DEFAULT_RESPONSE = "TaskMaster Bot is connected.";
    static final String MY_TASKS_RESPONSE = "Task list will be added in the next phase.";
    static final String STATISTICS_RESPONSE = "Statistics will be added later.";
    static final String SETTINGS_RESPONSE = "Settings will be added later.";

    private static final Logger log = LoggerFactory.getLogger(TaskmasterTelegramBot.class);

    private final TelegramProperties properties;
    private final TelegramClient telegramClient;
    private final MainMenuKeyboard mainMenuKeyboard;
    private final PriorityKeyboard priorityKeyboard;
    private final TaskConversationService conversationService;

    public TaskmasterTelegramBot(
            TelegramProperties properties,
            TelegramClient telegramClient,
            MainMenuKeyboard mainMenuKeyboard,
            PriorityKeyboard priorityKeyboard,
            TaskConversationService conversationService
    ) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.mainMenuKeyboard = mainMenuKeyboard;
        this.priorityKeyboard = priorityKeyboard;
        this.conversationService = conversationService;
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
        if (userId == null) {
            return;
        }

        log.info(
                "Received Telegram message: userId={}, chatId={}, text={}",
                userId,
                chatId,
                receivedText
        );

        String normalizedText = receivedText.trim();
        if ("/start".equals(normalizedText)) {
            conversationService.reset(userId);
            sendMessage(chatId, START_RESPONSE, mainMenuKeyboard.create());
            return;
        }

        if ("/cancel".equals(normalizedText)) {
            sendConversationReply(chatId, conversationService.cancel(userId));
            return;
        }

        if ("/help".equals(normalizedText)) {
            sendMessage(chatId, HELP_RESPONSE, null);
            return;
        }

        if (MainMenuKeyboard.ADD_TASK_BUTTON.equals(normalizedText)) {
            sendConversationReply(chatId, conversationService.start(userId));
            return;
        }

        String menuResponse = switch (normalizedText) {
            case MainMenuKeyboard.MY_TASKS_BUTTON -> MY_TASKS_RESPONSE;
            case MainMenuKeyboard.STATISTICS_BUTTON -> STATISTICS_RESPONSE;
            case MainMenuKeyboard.SETTINGS_BUTTON -> SETTINGS_RESPONSE;
            default -> null;
        };
        if (menuResponse != null) {
            conversationService.reset(userId);
            sendMessage(chatId, menuResponse, mainMenuKeyboard.create());
            return;
        }

        if (conversationService.isActive(userId)) {
            sendConversationReply(chatId, conversationService.handle(userId, receivedText));
            return;
        }

        sendMessage(chatId, DEFAULT_RESPONSE, null);
    }

    private void sendConversationReply(Long chatId, ConversationReply reply) {
        ReplyKeyboard keyboard = switch (reply.keyboardType()) {
            case MAIN_MENU -> mainMenuKeyboard.create();
            case PRIORITY -> priorityKeyboard.create();
            case REMOVE -> ReplyKeyboardRemove.builder().removeKeyboard(true).build();
            case NONE -> null;
        };
        sendMessage(chatId, reply.text(), keyboard);
    }

    private void sendMessage(Long chatId, String text, ReplyKeyboard replyKeyboard) {
        SendMessage.SendMessageBuilder<?, ?> responseBuilder = SendMessage.builder()
                .chatId(chatId)
                .text(text);

        if (replyKeyboard != null) {
            responseBuilder.replyMarkup(replyKeyboard);
        }

        try {
            telegramClient.execute(responseBuilder.build());
        } catch (TelegramApiException exception) {
            log.error(
                    "Failed to send Telegram message: chatId={}, errorType={}",
                    chatId,
                    exception.getClass().getSimpleName()
            );
        }
    }
}

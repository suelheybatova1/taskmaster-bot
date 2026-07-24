package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.TelegramProperties;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.User;
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
    static final String STATISTICS_RESPONSE = "Statistics will be added later.";
    static final String SETTINGS_RESPONSE = "Settings will be added later.";

    private static final Logger log = LoggerFactory.getLogger(TaskmasterTelegramBot.class);

    private final TelegramProperties properties;
    private final TelegramClient telegramClient;
    private final MainMenuKeyboard mainMenuKeyboard;
    private final PriorityKeyboard priorityKeyboard;
    private final TaskConversationService conversationService;
    private final TaskService taskService;
    private final TaskListFormatter taskListFormatter;
    private final TaskPaginationKeyboard taskPaginationKeyboard;
    private final TaskPageCallback taskPageCallback;

    public TaskmasterTelegramBot(
            TelegramProperties properties,
            TelegramClient telegramClient,
            MainMenuKeyboard mainMenuKeyboard,
            PriorityKeyboard priorityKeyboard,
            TaskConversationService conversationService,
            TaskService taskService,
            TaskListFormatter taskListFormatter,
            TaskPaginationKeyboard taskPaginationKeyboard,
            TaskPageCallback taskPageCallback
    ) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.mainMenuKeyboard = mainMenuKeyboard;
        this.priorityKeyboard = priorityKeyboard;
        this.conversationService = conversationService;
        this.taskService = taskService;
        this.taskListFormatter = taskListFormatter;
        this.taskPaginationKeyboard = taskPaginationKeyboard;
        this.taskPageCallback = taskPageCallback;
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
        if (update == null) {
            return;
        }
        if (update.hasCallbackQuery()) {
            consumeCallback(update.getCallbackQuery());
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText()) {
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

        if (MainMenuKeyboard.MY_TASKS_BUTTON.equals(normalizedText)) {
            conversationService.reset(userId);
            sendTasksPage(chatId, userId, 0);
            return;
        }

        String menuResponse = switch (normalizedText) {
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
            sendConversationReply(
                    chatId,
                    conversationService.handle(
                            userId,
                            receivedText,
                            telegramUserData(message.getFrom(), chatId)
                    )
            );
            return;
        }

        sendMessage(chatId, DEFAULT_RESPONSE, null);
    }

    private void consumeCallback(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getFrom() == null) {
            return;
        }

        var requestedPage = taskPageCallback.parse(callbackQuery.getData());
        if (requestedPage.isEmpty() || callbackQuery.getMessage() == null) {
            answerCallback(callbackQuery.getId(), "Invalid task page.");
            return;
        }

        Long userId = callbackQuery.getFrom().getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        log.info(
                "Received Telegram callback: userId={}, chatId={}, data={}",
                userId,
                chatId,
                callbackQuery.getData()
        );

        answerCallback(callbackQuery.getId(), null);
        sendTasksPage(chatId, userId, requestedPage.getAsInt());
    }

    private void sendTasksPage(Long chatId, Long telegramUserId, int requestedPage) {
        TaskPage taskPage = taskService.getActiveTasks(telegramUserId, requestedPage);
        sendMessage(
                chatId,
                taskListFormatter.format(taskPage),
                taskPaginationKeyboard.create(taskPage)
        );
    }

    private void answerCallback(String callbackQueryId, String text) {
        if (callbackQueryId == null) {
            return;
        }

        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId);
        if (text != null) {
            builder.text(text);
        }

        try {
            telegramClient.execute(builder.build());
        } catch (TelegramApiException exception) {
            log.error(
                    "Failed to answer Telegram callback: errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private TelegramUserData telegramUserData(User user, Long chatId) {
        return new TelegramUserData(
                user.getId(),
                chatId,
                user.getUserName(),
                user.getFirstName(),
                user.getLastName()
        );
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

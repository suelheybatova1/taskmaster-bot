package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.TelegramProperties;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.dto.TaskDetails;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.service.TaskService;
import com.github.taskmasterbot.service.TaskTransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    static final String SETTINGS_RESPONSE = "⚙️ Settings";
    static final String TASK_NOT_FOUND_RESPONSE = "Task not found.";
    static final String OPTIMISTIC_LOCK_RESPONSE =
            "Task was changed. Please refresh and try again.";

    private static final Logger log = LoggerFactory.getLogger(TaskmasterTelegramBot.class);
    private static final Pattern PAGE_HEADER_PATTERN =
            Pattern.compile("(?m)^Page ([1-9]\\d{0,5})/[1-9]\\d{0,5}$");

    private final TelegramProperties properties;
    private final TelegramClient telegramClient;
    private final MainMenuKeyboard mainMenuKeyboard;
    private final PriorityKeyboard priorityKeyboard;
    private final TaskConversationService conversationService;
    private final TaskService taskService;
    private final TaskListFormatter taskListFormatter;
    private final TaskPaginationKeyboard taskPaginationKeyboard;
    private final TaskPageCallback taskPageCallback;
    private final TaskDeletionCallback taskDeletionCallback;
    private final TaskDeletionKeyboard taskDeletionKeyboard;
    private final TaskActionCallback taskActionCallback;
    private final TaskDetailsFormatter taskDetailsFormatter;
    private final TaskDetailsKeyboard taskDetailsKeyboard;
    private final Map<Long, Integer> currentTaskPages = new ConcurrentHashMap<>();
    private final Map<Long, PendingTaskDeletion> pendingTaskDeletions =
            new ConcurrentHashMap<>();
    private final Map<Long, Boolean> pendingDeleteAll = new ConcurrentHashMap<>();

    public TaskmasterTelegramBot(
            TelegramProperties properties,
            TelegramClient telegramClient,
            MainMenuKeyboard mainMenuKeyboard,
            PriorityKeyboard priorityKeyboard,
            TaskConversationService conversationService,
            TaskService taskService,
            TaskListFormatter taskListFormatter,
            TaskPaginationKeyboard taskPaginationKeyboard,
            TaskPageCallback taskPageCallback,
            TaskDeletionCallback taskDeletionCallback,
            TaskDeletionKeyboard taskDeletionKeyboard,
            TaskActionCallback taskActionCallback,
            TaskDetailsFormatter taskDetailsFormatter,
            TaskDetailsKeyboard taskDetailsKeyboard
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
        this.taskDeletionCallback = taskDeletionCallback;
        this.taskDeletionKeyboard = taskDeletionKeyboard;
        this.taskActionCallback = taskActionCallback;
        this.taskDetailsFormatter = taskDetailsFormatter;
        this.taskDetailsKeyboard = taskDetailsKeyboard;
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
        clearDeletionConfirmations(userId);

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

        if (MainMenuKeyboard.STATISTICS_BUTTON.equals(normalizedText)) {
            conversationService.reset(userId);
            sendMessage(chatId, STATISTICS_RESPONSE, mainMenuKeyboard.create());
            return;
        }

        if (MainMenuKeyboard.SETTINGS_BUTTON.equals(normalizedText)) {
            conversationService.reset(userId);
            sendMessage(chatId, SETTINGS_RESPONSE, taskDeletionKeyboard.settings());
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
        if (callbackQuery == null
                || callbackQuery.getFrom() == null
                || callbackQuery.getMessage() == null) {
            return;
        }

        Long userId = callbackQuery.getFrom().getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        String callbackData = callbackQuery.getData();
        log.info(
                "Received Telegram callback: userId={}, chatId={}, data={}",
                userId,
                chatId,
                callbackData
        );

        var requestedPage = taskPageCallback.parse(callbackData);
        if (requestedPage.isPresent()) {
            answerCallback(callbackQuery.getId(), null);
            sendTasksPage(chatId, userId, requestedPage.getAsInt());
            return;
        }

        var deletion = taskDeletionCallback.parse(callbackData);
        if (deletion.isPresent()) {
            answerCallback(callbackQuery.getId(), null);
            try {
                handleTaskDeletion(
                        chatId,
                        userId,
                        deletion.orElseThrow(),
                        pageNumber(callbackQuery.getMessage(), userId)
                );
            } catch (ObjectOptimisticLockingFailureException exception) {
                sendMessage(chatId, OPTIMISTIC_LOCK_RESPONSE, null);
            }
            return;
        }

        var taskAction = taskActionCallback.parse(callbackData);
        if (taskAction.isPresent()) {
            answerCallback(callbackQuery.getId(), null);
            handleTaskAction(
                    chatId,
                    userId,
                    taskAction.orElseThrow(),
                    pageNumber(callbackQuery.getMessage(), userId)
            );
            return;
        }

        if (handleDeleteAllCallback(chatId, userId, callbackData)) {
            answerCallback(callbackQuery.getId(), null);
            return;
        }

        answerCallback(callbackQuery.getId(), "Invalid action.");
    }

    private void handleTaskAction(
            Long chatId,
            Long userId,
            TaskActionCallback.ParsedCallback callback,
            int originatingPage
    ) {
        if (callback.action() == TaskActionCallback.Action.VIEW) {
            currentTaskPages.put(userId, originatingPage);
            sendTaskDetails(chatId, userId, callback.taskId());
            return;
        }

        try {
            TaskTransitionResult result = switch (callback.action()) {
                case START -> taskService.startTask(userId, callback.taskId());
                case COMPLETE -> taskService.completeTask(userId, callback.taskId());
                case VIEW -> throw new IllegalStateException("View handled separately");
            };
            sendTransitionResult(chatId, userId, callback, result);
        } catch (ObjectOptimisticLockingFailureException exception) {
            sendMessage(chatId, OPTIMISTIC_LOCK_RESPONSE, null);
        }
    }

    private void sendTransitionResult(
            Long chatId,
            Long userId,
            TaskActionCallback.ParsedCallback callback,
            TaskTransitionResult result
    ) {
        if (result == TaskTransitionResult.NOT_FOUND) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        String response = switch (result) {
            case SUCCESS -> callback.action() == TaskActionCallback.Action.START
                    ? "🚧 Task #%d is now in progress.".formatted(callback.taskId())
                    : "✅ Task #%d completed.".formatted(callback.taskId());
            case ALREADY_IN_PROGRESS -> "Task is already in progress.";
            case ALREADY_COMPLETED -> "Task is already completed.";
            case NOT_FOUND -> throw new IllegalStateException("Handled before switch");
        };
        sendMessage(chatId, response, null);
        sendTaskDetails(chatId, userId, callback.taskId());
    }

    private void sendTaskDetails(Long chatId, Long userId, long taskId) {
        var task = taskService.getTaskDetails(userId, taskId);
        if (task.isEmpty()) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        TaskDetails details = task.orElseThrow();
        int backPage = currentTaskPages.getOrDefault(userId, 0);
        sendMessage(
                chatId,
                taskDetailsFormatter.format(details),
                taskDetailsKeyboard.create(details, backPage)
        );
    }

    private void handleTaskDeletion(
            Long chatId,
            Long userId,
            TaskDeletionCallback.ParsedCallback callback,
            int originatingPage
    ) {
        switch (callback.action()) {
            case REQUEST ->
                    requestTaskDeletion(chatId, userId, callback.taskId(), originatingPage);
            case CONFIRM -> confirmTaskDeletion(chatId, userId, callback.taskId());
            case CANCEL -> cancelTaskDeletion(chatId, userId, callback.taskId());
        }
    }

    private void requestTaskDeletion(
            Long chatId,
            Long userId,
            long taskId,
            int originatingPage
    ) {
        var title = taskService.findOwnedTaskTitle(userId, taskId);
        if (title.isEmpty()) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        pendingTaskDeletions.put(
                userId,
                new PendingTaskDeletion(taskId, originatingPage)
        );
        pendingDeleteAll.remove(userId);
        sendMessage(
                chatId,
                """
                        ⚠️ Delete task #%d?

                        %s

                        This action cannot be undone."""
                        .formatted(taskId, title.orElseThrow()),
                taskDeletionKeyboard.confirmTask(taskId)
        );
    }

    private void confirmTaskDeletion(Long chatId, Long userId, long taskId) {
        PendingTaskDeletion pending = pendingTaskDeletions.remove(userId);
        if (pending == null || pending.taskId() != taskId) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        if (!taskService.deleteTask(userId, taskId)) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        sendMessage(chatId, "🗑 Task #%d deleted.".formatted(taskId), null);
        sendTasksPage(chatId, userId, pending.pageNumber());
    }

    private void cancelTaskDeletion(Long chatId, Long userId, long taskId) {
        PendingTaskDeletion pending = pendingTaskDeletions.get(userId);
        if (pending == null || pending.taskId() != taskId) {
            sendMessage(chatId, TASK_NOT_FOUND_RESPONSE, null);
            return;
        }

        pendingTaskDeletions.remove(userId, pending);
        sendMessage(chatId, "Task deletion cancelled.", null);
        sendTasksPage(chatId, userId, pending.pageNumber());
    }

    private boolean handleDeleteAllCallback(Long chatId, Long userId, String callbackData) {
        if (TaskDeletionKeyboard.DELETE_ALL_CALLBACK.equals(callbackData)) {
            pendingDeleteAll.put(userId, true);
            pendingTaskDeletions.remove(userId);
            sendMessage(
                    chatId,
                    """
                            ⚠️ Delete all tasks?

                            This will permanently delete all of your tasks.

                            This action cannot be undone.""",
                    taskDeletionKeyboard.confirmAll()
            );
            return true;
        }

        if (TaskDeletionKeyboard.DELETE_ALL_CANCEL_CALLBACK.equals(callbackData)) {
            pendingDeleteAll.remove(userId);
            sendMessage(chatId, "Delete all cancelled.", null);
            return true;
        }

        if (!TaskDeletionKeyboard.DELETE_ALL_CONFIRM_CALLBACK.equals(callbackData)) {
            return false;
        }

        if (pendingDeleteAll.remove(userId) == null) {
            sendMessage(chatId, "Deletion confirmation expired.", null);
            return true;
        }

        int deleted = taskService.deleteAllTasks(userId);
        if (deleted == 0) {
            sendMessage(chatId, "📭 You have no tasks to delete.", null);
        } else {
            sendMessage(
                    chatId,
                    """
                            🗑 All tasks deleted.

                            Deleted: %d"""
                            .formatted(deleted),
                    null
            );
        }
        currentTaskPages.remove(userId);
        return true;
    }

    private int pageNumber(MaybeInaccessibleMessage callbackMessage, Long userId) {
        if (callbackMessage instanceof Message message && message.getText() != null) {
            var matcher = PAGE_HEADER_PATTERN.matcher(message.getText());
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1)) - 1;
            }
        }
        return currentTaskPages.getOrDefault(userId, 0);
    }

    private void clearDeletionConfirmations(Long userId) {
        pendingTaskDeletions.remove(userId);
        pendingDeleteAll.remove(userId);
    }

    private void sendTasksPage(Long chatId, Long telegramUserId, int requestedPage) {
        TaskPage taskPage = taskService.getActiveTasks(telegramUserId, requestedPage);
        currentTaskPages.put(telegramUserId, taskPage.pageNumber());
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

    private record PendingTaskDeletion(long taskId, int pageNumber) {
    }
}

package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.config.TelegramProperties;
import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TaskListItem;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;
import com.github.taskmasterbot.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskmasterTelegramBotTests {

    private static final long CHAT_ID = 1001L;
    private static final long USER_ID = 2002L;
    private static final long SECOND_USER_ID = 3003L;
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Baku");

    private TelegramClient telegramClient;
    private TaskmasterTelegramBot bot;
    private InMemoryConversationStore conversationStore;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        taskService = mock(TaskService.class);
        when(taskService.createTask(any(CreateTaskCommand.class)))
                .thenAnswer(invocation -> savedTask(invocation.getArgument(0)));
        when(taskService.getActiveTasks(anyLong(), anyInt()))
                .thenReturn(new TaskPage(List.of(), 0, 0));
        conversationStore = new InMemoryConversationStore();
        TaskConversationService conversationService = new TaskConversationService(
                conversationStore,
                new ApplicationProperties(TIME_ZONE),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), TIME_ZONE),
                taskService
        );
        bot = new TaskmasterTelegramBot(
                new TelegramProperties("test_bot", "test-token"),
                telegramClient,
                new MainMenuKeyboard(),
                new PriorityKeyboard(),
                conversationService,
                taskService,
                new TaskListFormatter(new ApplicationProperties(TIME_ZONE)),
                new TaskPaginationKeyboard(new TaskPageCallback()),
                new TaskPageCallback()
        );
    }

    @Test
    void startsTaskCreation() throws Exception {
        SendMessage response = send(MainMenuKeyboard.ADD_TASK_BUTTON);

        assertThat(response.getText()).isEqualTo(TaskConversationService.TITLE_PROMPT);
        assertThat(conversationStore.isActive(USER_ID)).isTrue();
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        send(MainMenuKeyboard.ADD_TASK_BUTTON);

        SendMessage response = send("   ");

        assertThat(response.getText()).contains("Title cannot be blank");
        assertThat(send("A valid title").getText())
                .isEqualTo(TaskConversationService.DESCRIPTION_PROMPT);
    }

    @Test
    void rejectsTitleLongerThan255Characters() throws Exception {
        send(MainMenuKeyboard.ADD_TASK_BUTTON);

        SendMessage response = send("a".repeat(256));

        assertThat(response.getText()).contains("255 characters or fewer");
        assertThat(send("A valid title").getText())
                .isEqualTo(TaskConversationService.DESCRIPTION_PROMPT);
    }

    @Test
    void acceptsDescriptionTextAndShowsPriorityKeyboard() throws Exception {
        advanceToDescription();

        SendMessage response = send("Detailed description");

        assertPriorityResponse(response);
    }

    @Test
    void skipsDescriptionAndShowsPriorityKeyboard() throws Exception {
        advanceToDescription();

        SendMessage response = send("/skip");

        assertPriorityResponse(response);
    }

    @Test
    void rejectsDescriptionLongerThan2000Characters() throws Exception {
        advanceToDescription();

        SendMessage response = send("d".repeat(2001));

        assertThat(response.getText()).contains("2000 characters or fewer");
        assertPriorityResponse(send("/skip"));
    }

    @Test
    void rejectsInvalidPriorityWithoutAdvancing() throws Exception {
        advanceToPriority();

        SendMessage response = send("Urgent");

        assertThat(response.getText()).contains("select one of the priority buttons");
        assertPriorityKeyboard(response);
        assertThat(send(PriorityKeyboard.HIGH_BUTTON).getText())
                .isEqualTo(TaskConversationService.DEADLINE_PROMPT);
    }

    @Test
    void acceptsValidPriority() throws Exception {
        advanceToPriority();

        SendMessage response = send(PriorityKeyboard.MEDIUM_BUTTON);

        assertThat(response.getText()).isEqualTo(TaskConversationService.DEADLINE_PROMPT);
        assertThat(response.getReplyMarkup()).isInstanceOf(ReplyKeyboardRemove.class);
        assertThat(((ReplyKeyboardRemove) response.getReplyMarkup()).getRemoveKeyboard()).isTrue();
    }

    @Test
    void rejectsInvalidDeadlineWithoutAdvancing() throws Exception {
        advanceToDeadline();

        SendMessage response = send("tomorrow evening");

        assertThat(response.getText()).contains("Invalid deadline");
        assertThat(send("/skip").getText()).startsWith("✅ Task created successfully.");
    }

    @Test
    void rejectsPastDeadlineWithoutAdvancing() throws Exception {
        advanceToDeadline();

        SendMessage response = send("2025-12-31 23:59");

        assertThat(response.getText()).contains("Deadline must be in the future");
        assertThat(send("/skip").getText()).startsWith("✅ Task created successfully.");
    }

    @Test
    void skipsDeadlineAndRestoresMainMenu() throws Exception {
        advanceToDeadline();

        SendMessage response = send("/skip");

        assertThat(response.getText()).contains("Deadline: —");
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
    }

    @Test
    void createsSuccessfulDraftSummaryAndClearsSession() throws Exception {
        send(MainMenuKeyboard.ADD_TASK_BUTTON);
        send("Portfolio project");
        send("Finish the Telegram flow");
        send(PriorityKeyboard.HIGH_BUTTON);

        SendMessage response = send("2026-01-02 12:30");

        assertThat(response.getText()).isEqualTo("""
                ✅ Task created successfully.

                Task ID: 42

                Title: Portfolio project

                Priority: HIGH

                Deadline: 2026-01-02 12:30""");
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
    }

    @Test
    void cancelClearsDraftAndRestoresMainMenu() throws Exception {
        advanceToDescription();

        SendMessage response = send("/cancel");

        assertThat(response.getText()).isEqualTo(TaskConversationService.CANCEL_RESPONSE);
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
        assertThat(send("orphaned description").getText())
                .isEqualTo(TaskmasterTelegramBot.DEFAULT_RESPONSE);
    }

    @Test
    void startResetsActiveSessionAndRestoresMainMenu() throws Exception {
        advanceToDescription();

        SendMessage response = send("/start");

        assertThat(response.getText()).isEqualTo(TaskmasterTelegramBot.START_RESPONSE);
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
        assertThat(send("orphaned description").getText())
                .isEqualTo(TaskmasterTelegramBot.DEFAULT_RESPONSE);
    }

    @Test
    void menuSelectionCancelsActiveFlowBeforeHandlingSelection() throws Exception {
        advanceToDescription();

        SendMessage response = send(MainMenuKeyboard.STATISTICS_BUTTON);

        assertThat(response.getText()).isEqualTo(TaskmasterTelegramBot.STATISTICS_RESPONSE);
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
    }

    @Test
    void displaysEmptyTaskMessage() throws Exception {
        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText()).isEqualTo(TaskListFormatter.EMPTY_TASKS_MESSAGE);
        assertThat(response.getReplyMarkup()).isNull();
        verify(taskService).getActiveTasks(USER_ID, 0);
    }

    @Test
    void displaysOneActiveTask() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 0)).thenReturn(new TaskPage(
                List.of(taskItem(
                        12L,
                        "Buy groceries",
                        TaskPriority.HIGH,
                        TaskStatus.TODO,
                        Instant.parse("2026-12-31T14:00:00Z")
                )),
                0,
                1
        ));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText()).isEqualTo("""
                #12 — Buy groceries
                Priority: 🔴 High
                Status: 📝 Todo
                Deadline: 2026-12-31 18:00""");
        assertThat(response.getReplyMarkup()).isNull();
    }

    @Test
    void displaysMultipleTasksAndNoDeadlineLabel() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 0)).thenReturn(new TaskPage(
                List.of(
                        taskItem(
                                1L,
                                "First",
                                TaskPriority.LOW,
                                TaskStatus.IN_PROGRESS,
                                Instant.parse("2026-05-01T08:00:00Z")
                        ),
                        taskItem(
                                2L,
                                "Second",
                                TaskPriority.MEDIUM,
                                TaskStatus.TODO,
                                null
                        )
                ),
                0,
                1
        ));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText())
                .contains("#1 — First", "🚧 In progress", "#2 — Second", "Deadline: No deadline");
    }

    @Test
    void addsNextButtonWhenAnotherPageExists() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 0)).thenReturn(page(0, 2, 10));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().getFirst()).hasSize(1);
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getText()).isEqualTo("Next ▶");
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData())
                .isEqualTo("tasks:page:1");
    }

    @Test
    void handlesNextPageCallbackForCallbackUser() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 1)).thenReturn(page(1, 2, 1));

        SendMessage response = sendCallback(USER_ID, "tasks:page:1");

        verify(taskService).getActiveTasks(USER_ID, 1);
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getText())
                .isEqualTo("◀ Previous");
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData())
                .isEqualTo("tasks:page:0");
    }

    @Test
    void handlesPreviousPageCallback() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 0)).thenReturn(page(0, 2, 10));

        sendCallback(USER_ID, "tasks:page:0");

        verify(taskService).getActiveTasks(USER_ID, 0);
    }

    @Test
    void ignoresMalformedTaskCallbackSafely() throws Exception {
        clearInvocations(telegramClient);

        bot.consume(callbackUpdate(USER_ID, "tasks:page:not-a-number"));

        verify(taskService, never()).getActiveTasks(anyLong(), anyInt());
        List<Object> sentMethods = telegramClientInvocations();
        assertThat(sentMethods).hasSize(1);
        assertThat(sentMethods.getFirst()).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(((AnswerCallbackQuery) sentMethods.getFirst()).getText())
                .isEqualTo("Invalid task page.");
    }

    @Test
    void displaysNearestPageReturnedForOutOfRangeCallback() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 999))
                .thenReturn(page(1, 2, 1));

        SendMessage response = sendCallback(USER_ID, "tasks:page:999");

        verify(taskService).getActiveTasks(USER_ID, 999);
        assertThat(response.getText()).contains("#101 — Task 101");
    }

    @Test
    void keepsSessionsIsolatedByTelegramUser() throws Exception {
        send(USER_ID, MainMenuKeyboard.ADD_TASK_BUTTON);
        send(USER_ID, "First user's title");
        send(SECOND_USER_ID, MainMenuKeyboard.ADD_TASK_BUTTON);

        SendMessage firstUserResponse = send(USER_ID, "First user's description");
        SendMessage secondUserResponse = send(SECOND_USER_ID, "Second user's title");

        assertPriorityResponse(firstUserResponse);
        assertThat(secondUserResponse.getText())
                .isEqualTo(TaskConversationService.DESCRIPTION_PROMPT);
        assertThat(conversationStore.isActive(USER_ID)).isTrue();
        assertThat(conversationStore.isActive(SECOND_USER_ID)).isTrue();
    }

    @Test
    void helpDoesNotResetActiveConversation() throws Exception {
        advanceToDescription();

        assertThat(send("/help").getText()).isEqualTo(TaskmasterTelegramBot.HELP_RESPONSE);
        assertThat(send("Description after help").getText())
                .isEqualTo(TaskConversationService.PRIORITY_PROMPT);
    }

    @Test
    void unknownTextWithoutConversationUsesFallback() throws Exception {
        assertThat(send("hello").getText()).isEqualTo(TaskmasterTelegramBot.DEFAULT_RESPONSE);
    }

    @Test
    void ignoresUpdateWithoutMessage() throws Exception {
        clearInvocations(telegramClient);

        bot.consume(new Update());

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void ignoresNonTextMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setChat(chat(CHAT_ID));
        message.setFrom(user(USER_ID));
        update.setMessage(message);
        clearInvocations(telegramClient);

        bot.consume(update);

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void exposesConfiguredRegistrationData() {
        assertThat(bot.getBotToken()).isEqualTo("test-token");
        assertThat(bot.getBotUsername()).isEqualTo("test_bot");
        assertThat(bot.getUpdatesConsumer()).isSameAs(bot);
    }

    private void advanceToDescription() throws Exception {
        send(MainMenuKeyboard.ADD_TASK_BUTTON);
        send("Task title");
    }

    private void advanceToPriority() throws Exception {
        advanceToDescription();
        send("/skip");
    }

    private void advanceToDeadline() throws Exception {
        advanceToPriority();
        send(PriorityKeyboard.HIGH_BUTTON);
    }

    private SendMessage send(String text) throws Exception {
        return send(USER_ID, text);
    }

    private SendMessage send(long userId, String text) throws Exception {
        clearInvocations(telegramClient);
        bot.consume(textUpdate(userId, text));

        org.mockito.ArgumentCaptor<SendMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        return captor.getValue();
    }

    private SendMessage sendCallback(long userId, String callbackData) throws Exception {
        clearInvocations(telegramClient);
        bot.consume(callbackUpdate(userId, callbackData));

        return telegramClientInvocations().stream()
                .filter(SendMessage.class::isInstance)
                .map(SendMessage.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private List<Object> telegramClientInvocations() {
        return org.mockito.Mockito.mockingDetails(telegramClient)
                .getInvocations()
                .stream()
                .map(invocation -> invocation.getArgument(0))
                .toList();
    }

    private void assertPriorityResponse(SendMessage response) {
        assertThat(response.getText()).isEqualTo(TaskConversationService.PRIORITY_PROMPT);
        assertPriorityKeyboard(response);
    }

    private void assertPriorityKeyboard(SendMessage response) {
        assertThat(response.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);
        ReplyKeyboardMarkup keyboard = (ReplyKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(buttonTexts(keyboard.getKeyboard().getFirst()))
                .containsExactly("🟢 Low", "🟡 Medium", "🔴 High");
    }

    private void assertMainMenu(SendMessage response) {
        assertThat(response.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);
        ReplyKeyboardMarkup keyboard = (ReplyKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(buttonTexts(keyboard.getKeyboard().get(0)))
                .containsExactly("➕ Add task", "📋 My tasks");
        assertThat(buttonTexts(keyboard.getKeyboard().get(1)))
                .containsExactly("📊 Statistics", "⚙️ Settings");
    }

    private List<String> buttonTexts(
            org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow row
    ) {
        return row.stream().map(button -> button.getText()).toList();
    }

    private Update textUpdate(long userId, String text) {
        Message message = new Message();
        message.setText(text);
        message.setChat(chat(CHAT_ID));
        message.setFrom(user(userId));

        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private Update callbackUpdate(long userId, String callbackData) {
        Message message = new Message();
        message.setMessageId(50);
        message.setChat(chat(CHAT_ID));

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("callback-id");
        callbackQuery.setFrom(user(userId));
        callbackQuery.setMessage(message);
        callbackQuery.setData(callbackData);

        Update update = new Update();
        update.setCallbackQuery(callbackQuery);
        return update;
    }

    private org.telegram.telegrambots.meta.api.objects.chat.Chat chat(long id) {
        return new org.telegram.telegrambots.meta.api.objects.chat.Chat(id, "private");
    }

    private User user(long id) {
        return new User(id, "Test User", false);
    }

    private Task savedTask(CreateTaskCommand command) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(42L);
        when(task.getTitle()).thenReturn(command.title());
        when(task.getPriority()).thenReturn(command.priority());
        return task;
    }

    private TaskListItem taskItem(
            Long id,
            String title,
            TaskPriority priority,
            TaskStatus status,
            Instant deadline
    ) {
        return new TaskListItem(id, title, priority, status, deadline);
    }

    private TaskPage page(int pageNumber, int totalPages, int taskCount) {
        List<TaskListItem> tasks = java.util.stream.IntStream.range(0, taskCount)
                .mapToObj(index -> taskItem(
                        (long) (pageNumber * 100 + index + 1),
                        "Task " + (pageNumber * 100 + index + 1),
                        TaskPriority.LOW,
                        TaskStatus.TODO,
                        null
                ))
                .toList();
        return new TaskPage(tasks, pageNumber, totalPages);
    }
}

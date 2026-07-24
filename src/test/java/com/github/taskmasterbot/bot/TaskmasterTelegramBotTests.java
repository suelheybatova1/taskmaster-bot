package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.config.TelegramProperties;
import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TaskListItem;
import com.github.taskmasterbot.dto.TaskDetails;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;
import com.github.taskmasterbot.service.TaskService;
import com.github.taskmasterbot.service.TaskTransitionResult;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import java.util.Optional;

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
                .thenReturn(new TaskPage(List.of(), 0, 0, 0));
        conversationStore = new InMemoryConversationStore();
        TaskConversationService conversationService = new TaskConversationService(
                conversationStore,
                new ApplicationProperties(TIME_ZONE),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), TIME_ZONE),
                taskService
        );
        TaskPageCallback pageCallback = new TaskPageCallback();
        TaskDeletionCallback deletionCallback = new TaskDeletionCallback();
        TaskActionCallback actionCallback = new TaskActionCallback();
        TaskDetailsFormatter detailsFormatter =
                new TaskDetailsFormatter(new ApplicationProperties(TIME_ZONE));
        bot = new TaskmasterTelegramBot(
                new TelegramProperties("test_bot", "test-token"),
                telegramClient,
                new MainMenuKeyboard(),
                new PriorityKeyboard(),
                conversationService,
                taskService,
                new TaskListFormatter(new ApplicationProperties(TIME_ZONE)),
                new TaskPaginationKeyboard(pageCallback, deletionCallback, actionCallback),
                pageCallback,
                deletionCallback,
                new TaskDeletionKeyboard(deletionCallback),
                actionCallback,
                detailsFormatter,
                new TaskDetailsKeyboard(actionCallback, deletionCallback, pageCallback)
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
        assertThat(send("/skip").getText()).startsWith("✅ Task created!");
    }

    @Test
    void rejectsPastDeadlineWithoutAdvancing() throws Exception {
        advanceToDeadline();

        SendMessage response = send("2025-12-31 23:59");

        assertThat(response.getText()).contains("Deadline must be in the future");
        assertThat(send("/skip").getText()).startsWith("✅ Task created!");
    }

    @Test
    void skipsDeadlineAndRestoresMainMenu() throws Exception {
        advanceToDeadline();

        SendMessage response = send("/skip");

        assertThat(response.getText())
                .contains("📅 No deadline")
                .doesNotContain("Task ID:");
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
                ✅ Task created!

                📝 Portfolio project
                🔴 High
                📅 2026-01-02 12:30

                Use 📋 My tasks to view it.""");
        assertThat(response.getText()).doesNotContain("Task ID:");
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
                1,
                1
        ));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText()).isEqualTo("""
                📋 Active tasks: 1
                Page 1/1

                📝 #12 | Buy groceries
                🔴 High
                📅 2026-12-31 18:00""");
        assertDeleteButton(response, 12L);
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
                1,
                2
        ));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText())
                .isEqualTo("""
                        📋 Active tasks: 2
                        Page 1/1

                        🚧 #1 | First
                        🟢 Low
                        📅 2026-05-01 12:00

                        ────────────

                        📝 #2 | Second
                        🟡 Medium
                        📅 No deadline""")
                .doesNotContain("Priority:", "Status:", "Deadline:");
    }

    @Test
    void addsNextButtonWhenAnotherPageExists() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 0)).thenReturn(page(0, 2, 10));

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(21);
        assertThat(keyboard.getKeyboard().getLast()).hasSize(1);
        assertThat(keyboard.getKeyboard().getLast().getFirst().getText()).isEqualTo("Next ▶");
        assertThat(keyboard.getKeyboard().getLast().getFirst().getCallbackData())
                .isEqualTo("tasks:page:1");
    }

    @Test
    void handlesNextPageCallbackForCallbackUser() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 1)).thenReturn(page(1, 2, 1));

        SendMessage response = sendCallback(USER_ID, "tasks:page:1");

        verify(taskService).getActiveTasks(USER_ID, 1);
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getLast().getFirst().getText())
                .isEqualTo("◀ Previous");
        assertThat(keyboard.getKeyboard().getLast().getFirst().getCallbackData())
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
                .isEqualTo("Invalid action.");
    }

    @Test
    void displaysNearestPageReturnedForOutOfRangeCallback() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 999))
                .thenReturn(page(1, 2, 1));

        SendMessage response = sendCallback(USER_ID, "tasks:page:999");

        verify(taskService).getActiveTasks(USER_ID, 999);
        assertThat(response.getText())
                .contains("Page 2/2", "📝 #101 | Task 101");
    }

    @Test
    void opensOwnTodoTaskWithFullDetailsAndActions() throws Exception {
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.TODO, "Buy milk and bread", true)));

        SendMessage response = sendCallback(USER_ID, "task:view:12");

        assertThat(response.getText()).isEqualTo("""
                📝 Task #12

                Title: Buy groceries
                Description: Buy milk and bread
                Priority: 🔴 High
                Status: 📝 Todo
                Deadline: 2026-12-31 18:00
                Created: 2026-07-25 14:30""");
        assertThat(inlineButtonTexts(response))
                .containsExactly("▶ Start", "✅ Complete", "🗑 Delete", "⬅ Back to tasks");
    }

    @Test
    void showsInProgressTaskDetailsWithAllowedActions() throws Exception {
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.IN_PROGRESS, null, false)));

        SendMessage response = sendCallback(USER_ID, "task:view:12");

        assertThat(response.getText())
                .contains(
                        "Description: No description",
                        "Status: 🚧 In progress",
                        "Deadline: No deadline"
                );
        assertThat(inlineButtonTexts(response))
                .containsExactly("✅ Complete", "🗑 Delete", "⬅ Back to tasks");
    }

    @Test
    void showsCompletedTaskDetailsWithAllowedActions() throws Exception {
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.COMPLETED, null, false)));

        SendMessage response = sendCallback(USER_ID, "task:view:12");

        assertThat(response.getText()).contains("Status: ✅ Completed");
        assertThat(inlineButtonTexts(response))
                .containsExactly("🗑 Delete", "⬅ Back to tasks");
    }

    @Test
    void cannotOpenForeignOrMissingTask() throws Exception {
        when(taskService.getTaskDetails(USER_ID, 99L)).thenReturn(Optional.empty());

        assertThat(sendCallback(USER_ID, "task:view:99").getText())
                .isEqualTo(TaskmasterTelegramBot.TASK_NOT_FOUND_RESPONSE);
        verify(taskService).getTaskDetails(USER_ID, 99L);
    }

    @Test
    void startsTodoTaskAndRefreshesDetails() throws Exception {
        when(taskService.startTask(USER_ID, 12L)).thenReturn(TaskTransitionResult.SUCCESS);
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.IN_PROGRESS, null, false)));

        clearInvocations(telegramClient);
        bot.consume(callbackUpdate(USER_ID, "task:start:12"));

        assertThat(sentMessages())
                .extracting(SendMessage::getText)
                .containsExactly(
                        "🚧 Task #12 is now in progress.",
                        new TaskDetailsFormatter(new ApplicationProperties(TIME_ZONE))
                                .format(details(TaskStatus.IN_PROGRESS, null, false))
                );
    }

    @Test
    void handlesStartForAlreadyStartedAndCompletedTasks() throws Exception {
        when(taskService.startTask(USER_ID, 12L))
                .thenReturn(TaskTransitionResult.ALREADY_IN_PROGRESS);
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.IN_PROGRESS, null, false)));
        assertThat(sendCallback(USER_ID, "task:start:12").getText())
                .isEqualTo("Task is already in progress.");

        when(taskService.startTask(USER_ID, 12L))
                .thenReturn(TaskTransitionResult.ALREADY_COMPLETED);
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.COMPLETED, null, false)));
        assertThat(sendCallback(USER_ID, "task:start:12").getText())
                .isEqualTo("Task is already completed.");
    }

    @Test
    void completesTodoAndInProgressTasksAndRefreshesDetails() throws Exception {
        when(taskService.completeTask(USER_ID, 12L)).thenReturn(TaskTransitionResult.SUCCESS);
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.COMPLETED, null, false)));

        assertThat(sendCallback(USER_ID, "task:complete:12").getText())
                .isEqualTo("✅ Task #12 completed.");

        when(taskService.completeTask(USER_ID, 13L)).thenReturn(TaskTransitionResult.SUCCESS);
        when(taskService.getTaskDetails(USER_ID, 13L))
                .thenReturn(Optional.of(new TaskDetails(
                        13L, "In progress task", null, TaskPriority.MEDIUM,
                        TaskStatus.COMPLETED, null,
                        Instant.parse("2026-07-25T10:30:00Z"),
                        Instant.parse("2026-07-25T11:00:00Z")
                )));
        assertThat(sendCallback(USER_ID, "task:complete:13").getText())
                .isEqualTo("✅ Task #13 completed.");
    }

    @Test
    void handlesAlreadyCompletedMissingAndForeignStatusActions() throws Exception {
        when(taskService.completeTask(USER_ID, 12L))
                .thenReturn(TaskTransitionResult.ALREADY_COMPLETED);
        when(taskService.getTaskDetails(USER_ID, 12L))
                .thenReturn(Optional.of(details(TaskStatus.COMPLETED, null, false)));
        assertThat(sendCallback(USER_ID, "task:complete:12").getText())
                .isEqualTo("Task is already completed.");

        when(taskService.startTask(USER_ID, 99L)).thenReturn(TaskTransitionResult.NOT_FOUND);
        assertThat(sendCallback(USER_ID, "task:start:99").getText())
                .isEqualTo(TaskmasterTelegramBot.TASK_NOT_FOUND_RESPONSE);
        verify(taskService).startTask(USER_ID, 99L);
    }

    @Test
    void returnsOptimisticLockMessage() throws Exception {
        when(taskService.startTask(USER_ID, 12L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Task.class, 12L));

        assertThat(sendCallback(USER_ID, "task:start:12").getText())
                .isEqualTo(TaskmasterTelegramBot.OPTIMISTIC_LOCK_RESPONSE);
    }

    @Test
    void validatesMalformedTaskActionAndBackReturnsToStoredPage() throws Exception {
        clearInvocations(telegramClient);
        bot.consume(callbackUpdate(USER_ID, "task:view:not-a-number"));
        assertThat(telegramClientInvocations())
                .filteredOn(AnswerCallbackQuery.class::isInstance)
                .extracting(method -> ((AnswerCallbackQuery) method).getText())
                .containsExactly("Invalid action.");

        when(taskService.getActiveTasks(USER_ID, 1)).thenReturn(page(1, 2, 1));
        sendCallback(USER_ID, "tasks:page:1");
        when(taskService.getTaskDetails(USER_ID, 101L))
                .thenReturn(Optional.of(new TaskDetails(
                        101L, "Task 101", null, TaskPriority.LOW, TaskStatus.TODO,
                        null, Instant.parse("2026-07-25T10:30:00Z"), null
                )));
        SendMessage details = sendCallback(USER_ID, "task:view:101");

        assertThat(inlineCallbacks(details)).contains("tasks:page:1");
        sendCallback(USER_ID, "tasks:page:1");
        verify(taskService, org.mockito.Mockito.atLeastOnce()).getActiveTasks(USER_ID, 1);
    }

    @Test
    void requestsConfirmationBeforeDeletingTask() throws Exception {
        when(taskService.findOwnedTaskTitle(USER_ID, 12L))
                .thenReturn(Optional.of("Buy groceries"));

        SendMessage response = sendCallback(USER_ID, "task:delete:12");

        assertThat(response.getText()).isEqualTo("""
                ⚠️ Delete task #12?

                Buy groceries

                This action cannot be undone.""");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst())
                .extracting(button -> button.getText())
                .containsExactly("✅ Yes, delete", "❌ Cancel");
        assertThat(keyboard.getKeyboard().getFirst())
                .extracting(button -> button.getCallbackData())
                .containsExactly("task:delete-confirm:12", "task:delete-cancel:12");
        verify(taskService, never()).deleteTask(anyLong(), anyLong());
    }

    @Test
    void confirmsOwnTaskDeletionAndShowsNearestPage() throws Exception {
        when(taskService.getActiveTasks(USER_ID, 1)).thenReturn(page(1, 2, 1));
        sendCallback(USER_ID, "tasks:page:1");
        when(taskService.findOwnedTaskTitle(USER_ID, 101L))
                .thenReturn(Optional.of("Task 101"));
        sendCallback(USER_ID, "task:delete:101");
        when(taskService.deleteTask(USER_ID, 101L)).thenReturn(true);
        when(taskService.getActiveTasks(USER_ID, 1)).thenReturn(page(0, 1, 10));

        clearInvocations(telegramClient);
        clearInvocations(taskService);
        bot.consume(callbackUpdate(USER_ID, "task:delete-confirm:101"));

        verify(taskService).deleteTask(USER_ID, 101L);
        verify(taskService).getActiveTasks(USER_ID, 1);
        assertThat(sentMessages())
                .extracting(SendMessage::getText)
                .containsExactly(
                        "🗑 Task #101 deleted.",
                        taskListFormatterText(page(0, 1, 10))
                );
    }

    @Test
    void cancelTaskDeletionDoesNotDelete() throws Exception {
        when(taskService.findOwnedTaskTitle(USER_ID, 12L))
                .thenReturn(Optional.of("Buy groceries"));
        sendCallback(USER_ID, "task:delete:12");

        SendMessage response = sendCallback(USER_ID, "task:delete-cancel:12");

        assertThat(response.getText()).isEqualTo("Task deletion cancelled.");
        verify(taskService, never()).deleteTask(anyLong(), anyLong());
    }

    @Test
    void handlesMissingStaleAndMalformedTaskDeletionSafely() throws Exception {
        when(taskService.findOwnedTaskTitle(USER_ID, 99L)).thenReturn(Optional.empty());
        assertThat(sendCallback(USER_ID, "task:delete:99").getText())
                .isEqualTo(TaskmasterTelegramBot.TASK_NOT_FOUND_RESPONSE);
        assertThat(sendCallback(USER_ID, "task:delete-confirm:99").getText())
                .isEqualTo(TaskmasterTelegramBot.TASK_NOT_FOUND_RESPONSE);

        clearInvocations(telegramClient);
        bot.consume(callbackUpdate(USER_ID, "task:delete:not-a-number"));

        verify(taskService, never()).deleteTask(anyLong(), anyLong());
        assertThat(telegramClientInvocations())
                .filteredOn(AnswerCallbackQuery.class::isInstance)
                .extracting(method -> ((AnswerCallbackQuery) method).getText())
                .containsExactly("Invalid action.");
    }

    @Test
    void settingsOffersDeleteAllAndRequiresConfirmation() throws Exception {
        SendMessage settings = send(MainMenuKeyboard.SETTINGS_BUTTON);
        InlineKeyboardMarkup settingsKeyboard =
                (InlineKeyboardMarkup) settings.getReplyMarkup();
        assertThat(settingsKeyboard.getKeyboard().getFirst().getFirst().getCallbackData())
                .isEqualTo("tasks:delete-all");

        SendMessage confirmation = sendCallback(USER_ID, "tasks:delete-all");

        assertThat(confirmation.getText()).isEqualTo("""
                ⚠️ Delete all tasks?

                This will permanently delete all of your tasks.

                This action cannot be undone.""");
        verify(taskService, never()).deleteAllTasks(anyLong());
    }

    @Test
    void confirmsDeleteAllAndReportsCount() throws Exception {
        sendCallback(USER_ID, "tasks:delete-all");
        when(taskService.deleteAllTasks(USER_ID)).thenReturn(14);

        SendMessage response = sendCallback(USER_ID, "tasks:delete-all-confirm");

        assertThat(response.getText()).isEqualTo("""
                🗑 All tasks deleted.

                Deleted: 14""");
        verify(taskService).deleteAllTasks(USER_ID);
    }

    @Test
    void deleteAllHandlesEmptyUserAndCancel() throws Exception {
        sendCallback(USER_ID, "tasks:delete-all");
        SendMessage cancelled = sendCallback(USER_ID, "tasks:delete-all-cancel");
        assertThat(cancelled.getText()).isEqualTo("Delete all cancelled.");
        verify(taskService, never()).deleteAllTasks(anyLong());

        sendCallback(USER_ID, "tasks:delete-all");
        when(taskService.deleteAllTasks(USER_ID)).thenReturn(0);
        SendMessage empty = sendCallback(USER_ID, "tasks:delete-all-confirm");
        assertThat(empty.getText()).isEqualTo("📭 You have no tasks to delete.");
    }

    @Test
    void directAndRepeatedDeleteAllConfirmationAreSafe() throws Exception {
        assertThat(sendCallback(USER_ID, "tasks:delete-all-confirm").getText())
                .isEqualTo("Deletion confirmation expired.");
        verify(taskService, never()).deleteAllTasks(anyLong());

        sendCallback(USER_ID, "tasks:delete-all");
        when(taskService.deleteAllTasks(USER_ID)).thenReturn(1);
        sendCallback(USER_ID, "tasks:delete-all-confirm");
        assertThat(sendCallback(USER_ID, "tasks:delete-all-confirm").getText())
                .isEqualTo("Deletion confirmation expired.");
        verify(taskService).deleteAllTasks(USER_ID);
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

    private List<SendMessage> sentMessages() {
        return telegramClientInvocations().stream()
                .filter(SendMessage.class::isInstance)
                .map(SendMessage.class::cast)
                .toList();
    }

    private String taskListFormatterText(TaskPage taskPage) {
        return new TaskListFormatter(new ApplicationProperties(TIME_ZONE)).format(taskPage);
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

    private void assertDeleteButton(SendMessage response, long taskId) {
        assertThat(response.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getText())
                .isEqualTo("👁 Open #" + taskId);
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData())
                .isEqualTo("task:view:" + taskId);
        assertThat(keyboard.getKeyboard().get(1).getFirst().getText())
                .isEqualTo("🗑 Delete #" + taskId);
        assertThat(keyboard.getKeyboard().get(1).getFirst().getCallbackData())
                .isEqualTo("task:delete:" + taskId);
    }

    private List<String> inlineButtonTexts(SendMessage response) {
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        return keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText())
                .toList();
    }

    private List<String> inlineCallbacks(SendMessage response) {
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) response.getReplyMarkup();
        return keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData())
                .toList();
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

    private TaskDetails details(
            TaskStatus status,
            String description,
            boolean hasDeadline
    ) {
        return new TaskDetails(
                12L,
                "Buy groceries",
                description,
                TaskPriority.HIGH,
                status,
                hasDeadline ? Instant.parse("2026-12-31T14:00:00Z") : null,
                Instant.parse("2026-07-25T10:30:00Z"),
                status == TaskStatus.COMPLETED
                        ? Instant.parse("2026-07-25T11:00:00Z")
                        : null
        );
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
        long totalTasks = totalPages <= 1
                ? taskCount
                : (long) (totalPages - 1) * TaskService.TASKS_PER_PAGE + taskCount;
        return new TaskPage(tasks, pageNumber, totalPages, totalTasks);
    }
}

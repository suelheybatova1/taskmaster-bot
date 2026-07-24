package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskmasterTelegramBotTests {

    private static final long CHAT_ID = 1001L;
    private static final long USER_ID = 2002L;
    private static final long SECOND_USER_ID = 3003L;
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Baku");

    private TelegramClient telegramClient;
    private TaskmasterTelegramBot bot;
    private InMemoryConversationStore conversationStore;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        conversationStore = new InMemoryConversationStore();
        TaskConversationService conversationService = new TaskConversationService(
                conversationStore,
                new ApplicationProperties(TIME_ZONE),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), TIME_ZONE)
        );
        bot = new TaskmasterTelegramBot(
                new TelegramProperties("test_bot", "test-token"),
                telegramClient,
                new MainMenuKeyboard(),
                new PriorityKeyboard(),
                conversationService
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
        assertThat(send("/skip").getText()).startsWith("✅ Task draft created");
    }

    @Test
    void rejectsPastDeadlineWithoutAdvancing() throws Exception {
        advanceToDeadline();

        SendMessage response = send("2025-12-31 23:59");

        assertThat(response.getText()).contains("Deadline must be in the future");
        assertThat(send("/skip").getText()).startsWith("✅ Task draft created");
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
                ✅ Task draft created

                Title: Portfolio project
                Description: Finish the Telegram flow
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

        SendMessage response = send(MainMenuKeyboard.MY_TASKS_BUTTON);

        assertThat(response.getText()).isEqualTo(TaskmasterTelegramBot.MY_TASKS_RESPONSE);
        assertMainMenu(response);
        assertThat(conversationStore.isActive(USER_ID)).isFalse();
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

    private org.telegram.telegrambots.meta.api.objects.chat.Chat chat(long id) {
        return new org.telegram.telegrambots.meta.api.objects.chat.Chat(id, "private");
    }

    private User user(long id) {
        return new User(id, "Test User", false);
    }
}

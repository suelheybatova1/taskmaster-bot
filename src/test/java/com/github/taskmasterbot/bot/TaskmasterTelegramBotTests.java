package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskmasterTelegramBotTests {

    private static final long CHAT_ID = 1001L;
    private static final long USER_ID = 2002L;

    private TelegramClient telegramClient;
    private TaskmasterTelegramBot bot;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        bot = new TaskmasterTelegramBot(
                new TelegramProperties("test_bot", "test-token"),
                telegramClient
        );
    }

    @ParameterizedTest
    @CsvSource({
            "/start, '👋 Welcome to TaskMaster Bot!\n\nThis bot will help you manage your daily tasks.'",
            "/help, 'Available commands:\n\n/start\n/help'",
            "hello, 'TaskMaster Bot is connected.'"
    })
    void repliesToTextMessages(String incomingText, String expectedResponse) throws Exception {
        bot.consume(textUpdate(incomingText));

        ArgumentCaptor<SendMessage> responseCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(responseCaptor.capture());
        SendMessage response = responseCaptor.getValue();

        assertThat(response.getChatId()).isEqualTo(String.valueOf(CHAT_ID));
        assertThat(response.getText()).isEqualTo(expectedResponse);
    }

    @Test
    void ignoresUpdateWithoutMessage() throws Exception {
        bot.consume(new Update());

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void ignoresNonTextMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setChat(chat(CHAT_ID));
        update.setMessage(message);

        bot.consume(update);

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void exposesConfiguredRegistrationData() {
        assertThat(bot.getBotToken()).isEqualTo("test-token");
        assertThat(bot.getBotUsername()).isEqualTo("test_bot");
        assertThat(bot.getUpdatesConsumer()).isSameAs(bot);
    }

    private Update textUpdate(String text) {
        Message message = new Message();
        message.setText(text);
        message.setChat(chat(CHAT_ID));
        message.setFrom(user(USER_ID));

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

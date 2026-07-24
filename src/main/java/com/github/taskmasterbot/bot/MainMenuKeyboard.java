package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

@Component
public class MainMenuKeyboard {

    public static final String ADD_TASK_BUTTON = "➕ Add task";
    public static final String MY_TASKS_BUTTON = "📋 My tasks";
    public static final String STATISTICS_BUTTON = "📊 Statistics";
    public static final String SETTINGS_BUTTON = "⚙️ Settings";

    public ReplyKeyboardMarkup create() {
        KeyboardRow taskRow = new KeyboardRow(ADD_TASK_BUTTON, MY_TASKS_BUTTON);
        KeyboardRow optionsRow = new KeyboardRow(STATISTICS_BUTTON, SETTINGS_BUTTON);

        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(taskRow, optionsRow))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .isPersistent(true)
                .build();
    }
}

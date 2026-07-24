package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

@Component
public class PriorityKeyboard {

    public static final String LOW_BUTTON = "🟢 Low";
    public static final String MEDIUM_BUTTON = "🟡 Medium";
    public static final String HIGH_BUTTON = "🔴 High";

    public ReplyKeyboardMarkup create() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(new KeyboardRow(LOW_BUTTON, MEDIUM_BUTTON, HIGH_BUTTON)))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .isPersistent(true)
                .build();
    }
}

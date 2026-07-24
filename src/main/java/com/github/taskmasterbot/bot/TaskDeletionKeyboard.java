package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

@Component
public class TaskDeletionKeyboard {

    public static final String DELETE_ALL_CALLBACK = "tasks:delete-all";
    public static final String DELETE_ALL_CONFIRM_CALLBACK = "tasks:delete-all-confirm";
    public static final String DELETE_ALL_CANCEL_CALLBACK = "tasks:delete-all-cancel";

    private final TaskDeletionCallback callback;

    public TaskDeletionKeyboard(TaskDeletionCallback callback) {
        this.callback = callback;
    }

    public InlineKeyboardMarkup confirmTask(long taskId) {
        return keyboard(
                button("✅ Yes, delete", callback.confirm(taskId)),
                button("❌ Cancel", callback.cancel(taskId))
        );
    }

    public InlineKeyboardMarkup settings() {
        return keyboard(button("🗑 Delete all tasks", DELETE_ALL_CALLBACK));
    }

    public InlineKeyboardMarkup confirmAll() {
        return keyboard(
                button("✅ Yes, delete all", DELETE_ALL_CONFIRM_CALLBACK),
                button("❌ Cancel", DELETE_ALL_CANCEL_CALLBACK)
        );
    }

    private InlineKeyboardMarkup keyboard(InlineKeyboardButton... buttons) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(new InlineKeyboardRow(List.of(buttons))))
                .build();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }
}

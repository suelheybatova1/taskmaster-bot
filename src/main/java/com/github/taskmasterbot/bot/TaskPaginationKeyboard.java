package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.dto.TaskPage;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

@Component
public class TaskPaginationKeyboard {

    private final TaskPageCallback callback;

    public TaskPaginationKeyboard(TaskPageCallback callback) {
        this.callback = callback;
    }

    public InlineKeyboardMarkup create(TaskPage taskPage) {
        List<InlineKeyboardButton> buttons = new ArrayList<>(2);
        if (taskPage.hasPrevious()) {
            buttons.add(button("◀ Previous", taskPage.pageNumber() - 1));
        }
        if (taskPage.hasNext()) {
            buttons.add(button("Next ▶", taskPage.pageNumber() + 1));
        }

        if (buttons.isEmpty()) {
            return null;
        }

        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(new InlineKeyboardRow(buttons)))
                .build();
    }

    private InlineKeyboardButton button(String text, int pageNumber) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callback.create(pageNumber))
                .build();
    }
}

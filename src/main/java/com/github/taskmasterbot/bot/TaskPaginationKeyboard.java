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
    private final TaskDeletionCallback deletionCallback;
    private final TaskActionCallback actionCallback;

    public TaskPaginationKeyboard(
            TaskPageCallback callback,
            TaskDeletionCallback deletionCallback,
            TaskActionCallback actionCallback
    ) {
        this.callback = callback;
        this.deletionCallback = deletionCallback;
        this.actionCallback = actionCallback;
    }

    public InlineKeyboardMarkup create(TaskPage taskPage) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        taskPage.tasks().forEach(task -> {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("👁 Open #" + task.id())
                            .callbackData(actionCallback.view(task.id()))
                            .build()
            )));
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑 Delete #" + task.id())
                            .callbackData(deletionCallback.request(task.id()))
                            .build()
            )));
        });

        List<InlineKeyboardButton> buttons = new ArrayList<>(2);
        if (taskPage.hasPrevious()) {
            buttons.add(button("◀ Previous", taskPage.pageNumber() - 1));
        }
        if (taskPage.hasNext()) {
            buttons.add(button("Next ▶", taskPage.pageNumber() + 1));
        }

        if (!buttons.isEmpty()) {
            rows.add(new InlineKeyboardRow(buttons));
        }

        if (rows.isEmpty()) {
            return null;
        }

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private InlineKeyboardButton button(String text, int pageNumber) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callback.create(pageNumber))
                .build();
    }
}

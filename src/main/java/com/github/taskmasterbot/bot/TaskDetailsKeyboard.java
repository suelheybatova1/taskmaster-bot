package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.dto.TaskDetails;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

@Component
public class TaskDetailsKeyboard {

    private final TaskActionCallback actionCallback;
    private final TaskDeletionCallback deletionCallback;
    private final TaskPageCallback pageCallback;

    public TaskDetailsKeyboard(
            TaskActionCallback actionCallback,
            TaskDeletionCallback deletionCallback,
            TaskPageCallback pageCallback
    ) {
        this.actionCallback = actionCallback;
        this.deletionCallback = deletionCallback;
        this.pageCallback = pageCallback;
    }

    public InlineKeyboardMarkup create(TaskDetails task, int backPage) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        switch (task.status()) {
            case TODO -> {
                rows.add(row(button("▶ Start", actionCallback.start(task.id()))));
                rows.add(row(button("✅ Complete", actionCallback.complete(task.id()))));
            }
            case IN_PROGRESS ->
                    rows.add(row(button("✅ Complete", actionCallback.complete(task.id()))));
            case COMPLETED -> {
                // Completed tasks only support deletion and navigation.
            }
        }
        rows.add(row(button("🗑 Delete", deletionCallback.request(task.id()))));
        rows.add(row(button("⬅ Back to tasks", pageCallback.create(backPage))));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardRow row(InlineKeyboardButton button) {
        return new InlineKeyboardRow(List.of(button));
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }
}

package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.entity.TaskPriority;

import java.time.ZonedDateTime;

public class TaskDraft {

    private String title;
    private String description;
    private TaskPriority priority;
    private ZonedDateTime deadline;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public ZonedDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(ZonedDateTime deadline) {
        this.deadline = deadline;
    }
}

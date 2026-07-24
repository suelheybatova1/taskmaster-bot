package com.github.taskmasterbot.bot;

public class ConversationSession {

    private ConversationState state = ConversationState.WAITING_FOR_TITLE;
    private final TaskDraft draft = new TaskDraft();

    public ConversationState getState() {
        return state;
    }

    public void setState(ConversationState state) {
        this.state = state;
    }

    public TaskDraft getDraft() {
        return draft;
    }
}

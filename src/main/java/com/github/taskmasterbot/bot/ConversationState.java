package com.github.taskmasterbot.bot;

public enum ConversationState {
    IDLE,
    WAITING_FOR_TITLE,
    WAITING_FOR_DESCRIPTION,
    WAITING_FOR_PRIORITY,
    WAITING_FOR_DEADLINE
}

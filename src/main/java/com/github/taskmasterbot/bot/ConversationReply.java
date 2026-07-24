package com.github.taskmasterbot.bot;

record ConversationReply(String text, ReplyKeyboardType keyboardType) {

    static ConversationReply text(String text) {
        return new ConversationReply(text, ReplyKeyboardType.NONE);
    }

    static ConversationReply priority(String text) {
        return new ConversationReply(text, ReplyKeyboardType.PRIORITY);
    }

    static ConversationReply removeKeyboard(String text) {
        return new ConversationReply(text, ReplyKeyboardType.REMOVE);
    }

    static ConversationReply mainMenu(String text) {
        return new ConversationReply(text, ReplyKeyboardType.MAIN_MENU);
    }
}

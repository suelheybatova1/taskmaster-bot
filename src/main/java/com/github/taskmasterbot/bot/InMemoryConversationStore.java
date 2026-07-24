package com.github.taskmasterbot.bot;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryConversationStore {

    private final ConcurrentMap<Long, ConversationSession> sessions = new ConcurrentHashMap<>();

    public ConversationSession start(Long userId) {
        ConversationSession session = new ConversationSession();
        sessions.put(userId, session);
        return session;
    }

    public ConversationSession get(Long userId) {
        return sessions.get(userId);
    }

    public boolean isActive(Long userId) {
        return sessions.containsKey(userId);
    }

    public void clear(Long userId) {
        sessions.remove(userId);
    }
}

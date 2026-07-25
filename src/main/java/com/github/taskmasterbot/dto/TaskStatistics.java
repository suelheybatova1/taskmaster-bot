package com.github.taskmasterbot.dto;

public record TaskStatistics(
        long todoCount,
        long inProgressCount,
        long completedCount,
        long overdueCount,
        long dueTodayCount,
        long dueThisWeekCount,
        long totalCount,
        long completionRate
) {
}

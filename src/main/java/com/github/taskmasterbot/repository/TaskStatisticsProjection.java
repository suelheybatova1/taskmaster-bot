package com.github.taskmasterbot.repository;

public interface TaskStatisticsProjection {

    long getTodoCount();

    long getInProgressCount();

    long getCompletedCount();

    long getOverdueCount();

    long getDueTodayCount();

    long getDueThisWeekCount();

    long getTotalCount();
}

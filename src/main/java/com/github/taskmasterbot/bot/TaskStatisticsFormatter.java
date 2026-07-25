package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.dto.TaskStatistics;
import org.springframework.stereotype.Component;

@Component
public class TaskStatisticsFormatter {

    public String format(TaskStatistics statistics) {
        if (statistics.totalCount() == 0) {
            return """
                    📊 Task statistics

                    You have no tasks yet.

                    Create your first task with ➕ Add task.""";
        }

        return """
                📊 Task statistics

                📝 Todo: %d
                🚧 In progress: %d
                ✅ Completed: %d

                ⚠️ Overdue: %d
                📅 Due today: %d
                📆 Due this week: %d

                Total tasks: %d
                Completion rate: %d%%"""
                .formatted(
                        statistics.todoCount(),
                        statistics.inProgressCount(),
                        statistics.completedCount(),
                        statistics.overdueCount(),
                        statistics.dueTodayCount(),
                        statistics.dueThisWeekCount(),
                        statistics.totalCount(),
                        statistics.completionRate()
                );
    }
}

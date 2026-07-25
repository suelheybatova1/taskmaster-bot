package com.github.taskmasterbot.repository;

import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByTelegramUserTelegramUserIdOrderByCreatedAtDesc(Long telegramUserId);

    Optional<Task> findByIdAndTelegramUserTelegramUserId(Long id, Long telegramUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM Task task
            WHERE task.telegramUser.telegramUserId = :telegramUserId
            """)
    int deleteAllByTelegramUserId(@Param("telegramUserId") Long telegramUserId);

    @Query(
            value = """
                    SELECT
                      COUNT(*) FILTER (WHERE task.status = 'TODO') AS "todoCount",
                      COUNT(*) FILTER (WHERE task.status = 'IN_PROGRESS')
                          AS "inProgressCount",
                      COUNT(*) FILTER (WHERE task.status = 'COMPLETED')
                          AS "completedCount",
                      COUNT(*) FILTER (
                        WHERE task.status IN ('TODO', 'IN_PROGRESS')
                          AND task.deadline < :now
                      ) AS "overdueCount",
                      COUNT(*) FILTER (
                        WHERE task.status IN ('TODO', 'IN_PROGRESS')
                          AND task.deadline >= :startToday
                          AND task.deadline < :startTomorrow
                      ) AS "dueTodayCount",
                      COUNT(*) FILTER (
                        WHERE task.status IN ('TODO', 'IN_PROGRESS')
                          AND task.deadline >= :startToday
                          AND task.deadline < :startNextWeek
                      ) AS "dueThisWeekCount",
                      COUNT(*) AS "totalCount"
                    FROM tasks task
                    JOIN telegram_users telegram_user
                      ON telegram_user.id = task.telegram_user_id
                    WHERE telegram_user.telegram_user_id = :telegramUserId
                    """,
            nativeQuery = true
    )
    TaskStatisticsProjection aggregateStatistics(
            @Param("telegramUserId") Long telegramUserId,
            @Param("now") java.time.Instant now,
            @Param("startToday") java.time.Instant startToday,
            @Param("startTomorrow") java.time.Instant startTomorrow,
            @Param("startNextWeek") java.time.Instant startNextWeek
    );

    @Query(
            value = """
                    SELECT task
                    FROM Task task
                    WHERE task.telegramUser.telegramUserId = :telegramUserId
                      AND task.status IN :statuses
                    ORDER BY
                      CASE WHEN task.deadline IS NULL THEN 1 ELSE 0 END,
                      task.deadline ASC,
                      task.createdAt DESC
                    """,
            countQuery = """
                    SELECT count(task)
                    FROM Task task
                    WHERE task.telegramUser.telegramUserId = :telegramUserId
                      AND task.status IN :statuses
                    """
    )
    Page<Task> findActiveTasks(
            @Param("telegramUserId") Long telegramUserId,
            @Param("statuses") Collection<TaskStatus> statuses,
            Pageable pageable
    );
}

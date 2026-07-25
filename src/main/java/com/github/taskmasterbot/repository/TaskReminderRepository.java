package com.github.taskmasterbot.repository;

import com.github.taskmasterbot.entity.ReminderStatus;
import com.github.taskmasterbot.entity.TaskReminder;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface TaskReminderRepository extends JpaRepository<TaskReminder, Long> {

    @Query(value = """
            SELECT reminder.id
            FROM task_reminders reminder
            JOIN tasks task ON task.id = reminder.task_id
            WHERE reminder.status IN ('PENDING', 'PROCESSING')
              AND reminder.scheduled_for <= :now
              AND reminder.next_attempt_at <= :now
              AND task.status IN ('TODO', 'IN_PROGRESS')
            ORDER BY reminder.scheduled_for, reminder.id
            FOR UPDATE OF reminder SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> lockDueReminderIds(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @EntityGraph(attributePaths = {"task", "task.telegramUser"})
    List<TaskReminder> findAllByIdIn(Collection<Long> ids);

    List<TaskReminder> findAllByTaskIdOrderByScheduledFor(Long taskId);

    long countByTaskId(Long taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TaskReminder reminder
            SET reminder.status = com.github.taskmasterbot.entity.ReminderStatus.CANCELLED
            WHERE reminder.task.id = :taskId
              AND reminder.status IN :statuses
            """)
    int cancelForTask(
            @Param("taskId") Long taskId,
            @Param("statuses") Collection<ReminderStatus> statuses
    );
}

package com.github.taskmasterbot.repository;

import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByTelegramUserTelegramUserIdOrderByCreatedAtDesc(Long telegramUserId);

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

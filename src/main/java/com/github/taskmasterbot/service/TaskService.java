package com.github.taskmasterbot.service;

import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TaskListItem;
import com.github.taskmasterbot.dto.TaskDetails;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.dto.TaskStatistics;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskStatus;
import com.github.taskmasterbot.entity.TelegramUser;
import com.github.taskmasterbot.repository.TaskRepository;
import com.github.taskmasterbot.repository.TaskStatisticsProjection;
import com.github.taskmasterbot.repository.TelegramUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.EnumSet;
import java.util.Optional;
import java.time.Instant;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

@Service
public class TaskService {

    public static final int TASKS_PER_PAGE = 10;

    private static final EnumSet<TaskStatus> ACTIVE_STATUSES =
            EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    private final TelegramUserRepository telegramUserRepository;
    private final TaskRepository taskRepository;
    private final Clock clock;

    public TaskService(
            TelegramUserRepository telegramUserRepository,
            TaskRepository taskRepository,
            Clock clock
    ) {
        this.telegramUserRepository = telegramUserRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @Transactional
    public Task createTask(CreateTaskCommand command) {
        TelegramUser telegramUser = findOrCreateUser(command.user());
        Task task = new Task(
                telegramUser,
                command.title(),
                command.description(),
                command.priority(),
                command.deadline()
        );
        return taskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public TaskPage getActiveTasks(Long telegramUserId, int requestedPage) {
        int safePage = Math.max(requestedPage, 0);
        Page<Task> result = queryActiveTasks(telegramUserId, safePage);

        if (result.getTotalPages() == 0) {
            safePage = 0;
        } else if (safePage >= result.getTotalPages()) {
            safePage = result.getTotalPages() - 1;
            result = queryActiveTasks(telegramUserId, safePage);
        }

        return new TaskPage(
                result.getContent().stream()
                        .map(this::toListItem)
                        .toList(),
                safePage,
                result.getTotalPages(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Optional<String> findOwnedTaskTitle(Long telegramUserId, Long taskId) {
        return taskRepository.findByIdAndTelegramUserTelegramUserId(taskId, telegramUserId)
                .map(Task::getTitle);
    }

    @Transactional(readOnly = true)
    public Optional<TaskDetails> getTaskDetails(Long telegramUserId, Long taskId) {
        return taskRepository.findByIdAndTelegramUserTelegramUserId(taskId, telegramUserId)
                .map(this::toDetails);
    }

    @Transactional
    public TaskTransitionResult startTask(Long telegramUserId, Long taskId) {
        Optional<Task> task = taskRepository.findByIdAndTelegramUserTelegramUserId(
                taskId,
                telegramUserId
        );
        if (task.isEmpty()) {
            return TaskTransitionResult.NOT_FOUND;
        }
        if (task.orElseThrow().getStatus() == TaskStatus.IN_PROGRESS) {
            return TaskTransitionResult.ALREADY_IN_PROGRESS;
        }
        if (!task.orElseThrow().start()) {
            return TaskTransitionResult.ALREADY_COMPLETED;
        }
        return TaskTransitionResult.SUCCESS;
    }

    @Transactional
    public TaskTransitionResult completeTask(Long telegramUserId, Long taskId) {
        Optional<Task> task = taskRepository.findByIdAndTelegramUserTelegramUserId(
                taskId,
                telegramUserId
        );
        if (task.isEmpty()) {
            return TaskTransitionResult.NOT_FOUND;
        }
        if (!task.orElseThrow().complete(Instant.now(clock))) {
            return TaskTransitionResult.ALREADY_COMPLETED;
        }
        return TaskTransitionResult.SUCCESS;
    }

    @Transactional
    public boolean deleteTask(Long telegramUserId, Long taskId) {
        return taskRepository.findByIdAndTelegramUserTelegramUserId(taskId, telegramUserId)
                .map(task -> {
                    taskRepository.delete(task);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int deleteAllTasks(Long telegramUserId) {
        return taskRepository.deleteAllByTelegramUserId(telegramUserId);
    }

    @Transactional(readOnly = true)
    public TaskStatistics getStatistics(Long telegramUserId) {
        Instant now = Instant.now(clock);
        ZoneId timeZone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Instant startToday = today.atStartOfDay(timeZone).toInstant();
        Instant startTomorrow = today.plusDays(1).atStartOfDay(timeZone).toInstant();
        Instant startNextWeek = today
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(timeZone)
                .toInstant();

        TaskStatisticsProjection aggregate = taskRepository.aggregateStatistics(
                telegramUserId,
                now,
                startToday,
                startTomorrow,
                startNextWeek
        );
        long completionRate = aggregate.getTotalCount() == 0
                ? 0
                : Math.round(
                        aggregate.getCompletedCount() * 100.0 / aggregate.getTotalCount()
                );

        return new TaskStatistics(
                aggregate.getTodoCount(),
                aggregate.getInProgressCount(),
                aggregate.getCompletedCount(),
                aggregate.getOverdueCount(),
                aggregate.getDueTodayCount(),
                aggregate.getDueThisWeekCount(),
                aggregate.getTotalCount(),
                completionRate
        );
    }

    private Page<Task> queryActiveTasks(Long telegramUserId, int page) {
        return taskRepository.findActiveTasks(
                telegramUserId,
                ACTIVE_STATUSES,
                PageRequest.of(page, TASKS_PER_PAGE)
        );
    }

    private TaskListItem toListItem(Task task) {
        return new TaskListItem(
                task.getId(),
                task.getTitle(),
                task.getPriority(),
                task.getStatus(),
                task.getDeadline()
        );
    }

    private TaskDetails toDetails(Task task) {
        return new TaskDetails(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getPriority(),
                task.getStatus(),
                task.getDeadline(),
                task.getCreatedAt(),
                task.getCompletedAt()
        );
    }

    private TelegramUser findOrCreateUser(TelegramUserData data) {
        TelegramUser user = telegramUserRepository
                .findByTelegramUserId(data.telegramUserId())
                .orElseGet(() -> new TelegramUser(
                        data.telegramUserId(),
                        data.chatId(),
                        data.username(),
                        data.firstName(),
                        data.lastName()
                ));

        user.updateProfile(
                data.chatId(),
                data.username(),
                data.firstName(),
                data.lastName()
        );
        return telegramUserRepository.save(user);
    }
}

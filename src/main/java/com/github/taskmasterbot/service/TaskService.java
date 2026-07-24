package com.github.taskmasterbot.service;

import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TaskListItem;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskStatus;
import com.github.taskmasterbot.entity.TelegramUser;
import com.github.taskmasterbot.repository.TaskRepository;
import com.github.taskmasterbot.repository.TelegramUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.EnumSet;
import java.util.Optional;

@Service
public class TaskService {

    public static final int TASKS_PER_PAGE = 10;

    private static final EnumSet<TaskStatus> ACTIVE_STATUSES =
            EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    private final TelegramUserRepository telegramUserRepository;
    private final TaskRepository taskRepository;

    public TaskService(
            TelegramUserRepository telegramUserRepository,
            TaskRepository taskRepository
    ) {
        this.telegramUserRepository = telegramUserRepository;
        this.taskRepository = taskRepository;
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

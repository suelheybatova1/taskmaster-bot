package com.github.taskmasterbot.service;

import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TelegramUser;
import com.github.taskmasterbot.repository.TaskRepository;
import com.github.taskmasterbot.repository.TelegramUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

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

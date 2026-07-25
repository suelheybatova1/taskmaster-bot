package com.github.taskmasterbot.service;

import com.github.taskmasterbot.config.ReminderProperties;
import com.github.taskmasterbot.dto.ReminderDelivery;
import com.github.taskmasterbot.entity.ReminderStatus;
import com.github.taskmasterbot.entity.ReminderType;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskReminder;
import com.github.taskmasterbot.repository.TaskReminderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReminderService {
    private static final Duration MINIMUM_PROCESSING_LEASE = Duration.ofMinutes(1);

    private final TaskReminderRepository repository;
    private final Clock clock;
    private final ReminderProperties properties;

    public ReminderService(
            TaskReminderRepository repository, Clock clock, ReminderProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public void scheduleForTask(Task task) {
        if (task.getDeadline() == null) {
            return;
        }
        Instant now = clock.instant();
        List<TaskReminder> reminders = List.of(
                        new TaskReminder(task, ReminderType.DEADLINE_24_HOURS,
                                task.getDeadline().minus(Duration.ofHours(24))),
                        new TaskReminder(task, ReminderType.DEADLINE_1_HOUR,
                                task.getDeadline().minus(Duration.ofHours(1))),
                        new TaskReminder(task, ReminderType.DEADLINE_REACHED, task.getDeadline()))
                .stream()
                .filter(reminder -> !reminder.getScheduledFor().isBefore(now))
                .toList();
        repository.saveAll(reminders);
    }

    @Transactional
    public void cancelPendingForTask(Long taskId) {
        repository.cancelForTask(
                taskId, EnumSet.of(ReminderStatus.PENDING, ReminderStatus.PROCESSING));
    }

    @Transactional
    public List<ReminderDelivery> claimDueReminders() {
        Instant now = clock.instant();
        List<Long> ids = repository.lockDueReminderIds(now, properties.batchSize());
        if (ids.isEmpty()) {
            return List.of();
        }
        Duration configuredLease = Duration.ofMillis(Math.max(1L, properties.pollInterval()) * 2);
        Instant leaseUntil = now.plus(configuredLease.compareTo(MINIMUM_PROCESSING_LEASE) >= 0
                ? configuredLease : MINIMUM_PROCESSING_LEASE);
        return repository.findAllByIdIn(ids).stream()
                .map(reminder -> {
                    reminder.claim(leaseUntil);
                    Task task = reminder.getTask();
                    return new ReminderDelivery(
                            reminder.getId(),
                            task.getTelegramUser().getChatId(),
                            task.getTitle(),
                            task.getPriority(),
                            task.getDeadline(),
                            reminder.getReminderType());
                })
                .toList();
    }

    @Transactional
    public void markSent(long reminderId) {
        repository.findById(reminderId)
                .filter(reminder -> reminder.getStatus() == ReminderStatus.PROCESSING)
                .ifPresent(reminder -> reminder.markSent(clock.instant()));
    }

    @Transactional
    public void markFailed(long reminderId, String error) {
        Instant retryAt = clock.instant().plusMillis(Math.max(1L, properties.pollInterval()));
        repository.findById(reminderId)
                .filter(reminder -> reminder.getStatus() == ReminderStatus.PROCESSING)
                .ifPresent(reminder ->
                        reminder.markFailed(retryAt, properties.maxAttempts(), error));
    }
}

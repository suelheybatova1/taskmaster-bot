package com.github.taskmasterbot.service;

import com.github.taskmasterbot.bot.ReminderMessageFormatter;
import com.github.taskmasterbot.bot.TelegramNotificationSender;
import com.github.taskmasterbot.config.ApplicationProperties;
import com.github.taskmasterbot.config.ReminderProperties;
import com.github.taskmasterbot.dto.ReminderDelivery;
import com.github.taskmasterbot.entity.ReminderStatus;
import com.github.taskmasterbot.entity.ReminderType;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskReminder;
import com.github.taskmasterbot.repository.TaskReminderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReminderComponentsTests {
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Test
    void formatsEveryReminderType() {
        ReminderMessageFormatter formatter =
                new ReminderMessageFormatter(new ApplicationProperties(ZoneId.of("Asia/Baku")));

        assertThat(formatter.format(delivery(ReminderType.DEADLINE_24_HOURS)))
                .isEqualTo("⏰ Task reminder\n\n📝 Ship release\n🔴 High"
                        + "\n📅 Deadline in 24 hours\n🕒 2026-07-25 18:00");
        assertThat(formatter.format(delivery(ReminderType.DEADLINE_1_HOUR)))
                .contains("⏰ Task reminder", "📅 Deadline in 1 hour");
        assertThat(formatter.format(delivery(ReminderType.DEADLINE_REACHED)))
                .isEqualTo("⚠️ Task deadline reached\n\n📝 Ship release\n📅 2026-07-25 18:00");
    }

    @Test
    void schedulerDoesNothingForEmptyBatch() {
        ReminderService service = mock(ReminderService.class);
        TelegramNotificationSender sender = mock(TelegramNotificationSender.class);
        when(service.claimDueReminders()).thenReturn(List.of());

        new ReminderScheduler(service, sender).processDueReminders();

        verifyNoInteractions(sender);
    }

    @Test
    void schedulerMarksSuccessfulDeliverySent() {
        ReminderService service = mock(ReminderService.class);
        TelegramNotificationSender sender = mock(TelegramNotificationSender.class);
        ReminderDelivery delivery = delivery(ReminderType.DEADLINE_1_HOUR);
        when(service.claimDueReminders()).thenReturn(List.of(delivery));

        new ReminderScheduler(service, sender).processDueReminders();

        verify(sender).send(delivery);
        verify(service).markSent(7L);
    }

    @Test
    void schedulerRecordsFailedDeliveryWithoutStoppingBatch() {
        ReminderService service = mock(ReminderService.class);
        TelegramNotificationSender sender = mock(TelegramNotificationSender.class);
        ReminderDelivery first = delivery(ReminderType.DEADLINE_24_HOURS);
        ReminderDelivery second = new ReminderDelivery(
                8L, 99L, "Second", TaskPriority.LOW,
                NOW.plusSeconds(28_800), ReminderType.DEADLINE_REACHED);
        when(service.claimDueReminders()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("sensitive detail")).when(sender).send(first);

        new ReminderScheduler(service, sender).processDueReminders();

        verify(service).markFailed(7L, "IllegalStateException");
        verify(sender).send(second);
        verify(service).markSent(8L);
    }

    @Test
    void maxAttemptsMovesReminderToFailed() {
        TaskReminder reminder = new TaskReminder(
                mock(Task.class), ReminderType.DEADLINE_REACHED, NOW);

        for (int attempt = 0; attempt < 3; attempt++) {
            reminder.claim(NOW.plusSeconds(60));
            reminder.markFailed(NOW.plusSeconds(60), 3, "network");
        }

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.FAILED);
        assertThat(reminder.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void scheduleSkipsPastReminderTimesAndTasksWithoutDeadlines() {
        TaskReminderRepository repository = mock(TaskReminderRepository.class);
        ReminderService service = new ReminderService(
                repository,
                Clock.fixed(NOW, ZoneId.of("UTC")),
                new ReminderProperties(true, 60_000, 50, 3));
        Task futureTask = mock(Task.class);
        when(futureTask.getDeadline()).thenReturn(NOW.plusSeconds(7_200));
        Task noDeadline = mock(Task.class);

        service.scheduleForTask(futureTask);
        service.scheduleForTask(noDeadline);

        verify(repository).saveAll(argThat(reminders -> {
            List<TaskReminder> values = (List<TaskReminder>) reminders;
            return values.size() == 2
                    && values.stream().noneMatch(value ->
                            value.getReminderType() == ReminderType.DEADLINE_24_HOURS);
        }));
    }

    private static ReminderDelivery delivery(ReminderType type) {
        return new ReminderDelivery(
                7L, 99L, "Ship release", TaskPriority.HIGH,
                Instant.parse("2026-07-25T14:00:00Z"), type);
    }
}

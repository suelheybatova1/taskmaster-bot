package com.github.taskmasterbot.service;

import com.github.taskmasterbot.config.ReminderProperties;
import com.github.taskmasterbot.entity.ReminderStatus;
import com.github.taskmasterbot.entity.ReminderType;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskReminder;
import com.github.taskmasterbot.entity.TelegramUser;
import com.github.taskmasterbot.repository.TaskReminderRepository;
import com.github.taskmasterbot.repository.TaskRepository;
import com.github.taskmasterbot.repository.TelegramUserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ReminderService.class, ReminderServiceIntegrationIT.Configuration.class})
@Testcontainers
class ReminderServiceIntegrationIT {
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("reminder_test")
            .withUsername("reminder_test")
            .withPassword("reminder_test");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Baku"));
        }

        @Bean
        ReminderProperties reminderProperties() {
            return new ReminderProperties(false, 60_000, 50, 3);
        }
    }

    @Autowired ReminderService service;
    @Autowired TaskReminderRepository reminderRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired TelegramUserRepository userRepository;
    @Autowired EntityManager entityManager;

    @Test
    void createsThreePersistentRemindersForDistantDeadline() {
        Task task = persistTask(NOW.plusSeconds(172_800));

        service.scheduleForTask(task);
        entityManager.flush();

        assertThat(reminderRepository.findAllByTaskIdOrderByScheduledFor(task.getId()))
                .extracting(TaskReminder::getReminderType)
                .containsExactly(
                        ReminderType.DEADLINE_24_HOURS,
                        ReminderType.DEADLINE_1_HOUR,
                        ReminderType.DEADLINE_REACHED);
    }

    @Test
    void createsOnlyRelevantFutureRemindersAndNoneWithoutDeadline() {
        Task nearDeadline = persistTask(NOW.plusSeconds(7_200));
        Task noDeadline = persistTask(null);

        service.scheduleForTask(nearDeadline);
        service.scheduleForTask(noDeadline);
        entityManager.flush();

        assertThat(reminderRepository.findAllByTaskIdOrderByScheduledFor(nearDeadline.getId()))
                .extracting(TaskReminder::getReminderType)
                .containsExactly(ReminderType.DEADLINE_1_HOUR, ReminderType.DEADLINE_REACHED);
        assertThat(reminderRepository.countByTaskId(noDeadline.getId())).isZero();
    }

    @Test
    void claimsDueReminderOnceAndMarksItSent() {
        Task task = persistTask(NOW.plusSeconds(3_600));
        TaskReminder reminder = reminderRepository.saveAndFlush(
                new TaskReminder(task, ReminderType.DEADLINE_1_HOUR, NOW));

        var firstClaim = service.claimDueReminders();
        var secondClaim = service.claimDueReminders();
        service.markSent(reminder.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(firstClaim).singleElement().satisfies(delivery -> {
            assertThat(delivery.chatId()).isEqualTo(9001L);
            assertThat(delivery.title()).isEqualTo("Reminder task");
        });
        assertThat(secondClaim).isEmpty();
        TaskReminder sent = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo(1);
        assertThat(sent.getSentAt()).isEqualTo(NOW);
    }

    @Test
    void cancellationAndCascadeDeletionPreventDelivery() {
        Task task = persistTask(NOW.plusSeconds(3_600));
        reminderRepository.saveAndFlush(
                new TaskReminder(task, ReminderType.DEADLINE_1_HOUR, NOW));

        service.cancelPendingForTask(task.getId());
        entityManager.flush();
        assertThat(service.claimDueReminders()).isEmpty();

        taskRepository.delete(task);
        entityManager.flush();
        assertThat(reminderRepository.countByTaskId(task.getId())).isZero();
        assertThat(userRepository.findByTelegramUserId(8001L)).isPresent();
    }

    private Task persistTask(Instant deadline) {
        TelegramUser user = userRepository.findByTelegramUserId(8001L)
                .orElseGet(() -> userRepository.save(
                        new TelegramUser(8001L, 9001L, "owner", "Test", "User")));
        return taskRepository.saveAndFlush(
                new Task(user, "Reminder task", null, TaskPriority.HIGH, deadline));
    }
}

package com.github.taskmasterbot.service;

import com.github.taskmasterbot.dto.CreateTaskCommand;
import com.github.taskmasterbot.dto.TaskPage;
import com.github.taskmasterbot.dto.TelegramUserData;
import com.github.taskmasterbot.entity.Task;
import com.github.taskmasterbot.entity.TaskPriority;
import com.github.taskmasterbot.entity.TaskStatus;
import com.github.taskmasterbot.entity.TelegramUser;
import com.github.taskmasterbot.repository.TaskRepository;
import com.github.taskmasterbot.repository.TelegramUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({TaskService.class, TaskServiceIntegrationIT.ClockConfiguration.class})
@Testcontainers
class TaskServiceIntegrationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        Clock testClock() {
            return Clock.systemUTC();
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("taskmaster_test")
                    .withUsername("taskmaster_test")
                    .withPassword("taskmaster_test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTelegramUserAndPersistsTask() {
        Instant deadline = Instant.parse("2030-06-15T12:30:00Z");
        CreateTaskCommand command = command(
                1001L,
                2001L,
                "first_user",
                "Persistent task",
                "Stored in PostgreSQL",
                TaskPriority.HIGH,
                deadline
        );

        Task savedTask = taskService.createTask(command);
        entityManager.flush();

        assertThat(savedTask.getId()).isNotNull();
        assertThat(savedTask.getTitle()).isEqualTo("Persistent task");
        assertThat(savedTask.getDescription()).isEqualTo("Stored in PostgreSQL");
        assertThat(savedTask.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(savedTask.getDeadline()).isEqualTo(deadline);
        assertThat(savedTask.getCreatedAt()).isNotNull();
        assertThat(savedTask.getUpdatedAt()).isNotNull();
        assertThat(savedTask.getCompletedAt()).isNull();

        TelegramUser user = telegramUserRepository.findByTelegramUserId(1001L).orElseThrow();
        assertThat(user.getChatId()).isEqualTo(2001L);
        assertThat(user.getUsername()).isEqualTo("first_user");
        assertThat(taskRepository.findAllByTelegramUserTelegramUserIdOrderByCreatedAtDesc(1001L))
                .extracting(Task::getId)
                .containsExactly(savedTask.getId());
    }

    @Test
    void reusesTelegramUserAndRefreshesProfileData() {
        taskService.createTask(command(
                1002L,
                2002L,
                "old_name",
                "First task",
                null,
                TaskPriority.LOW,
                null
        ));
        taskService.createTask(command(
                1002L,
                9999L,
                "new_name",
                "Second task",
                null,
                TaskPriority.MEDIUM,
                null
        ));
        entityManager.flush();

        List<Task> tasks =
                taskRepository.findAllByTelegramUserTelegramUserIdOrderByCreatedAtDesc(1002L);
        TelegramUser user = telegramUserRepository.findByTelegramUserId(1002L).orElseThrow();

        assertThat(tasks).hasSize(2);
        assertThat(telegramUserRepository.count()).isEqualTo(1);
        assertThat(user.getChatId()).isEqualTo(9999L);
        assertThat(user.getUsername()).isEqualTo("new_name");
    }

    @Test
    void taskRelationIsLazy() {
        Task saved = taskService.createTask(command(
                1003L,
                2003L,
                null,
                "Lazy relation",
                null,
                TaskPriority.LOW,
                null
        ));
        entityManager.flush();
        entityManager.clear();

        Task reloaded = taskRepository.findById(saved.getId()).orElseThrow();
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        assertThat(persistenceUnitUtil.isLoaded(reloaded, "telegramUser")).isFalse();
    }

    @Test
    void flywayCreatesExpectedSchema() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('telegram_users', 'tasks')
                ORDER BY table_name
                """,
                String.class
        );

        assertThat(appliedMigrations).isEqualTo(2);
        assertThat(tables).containsExactly("tasks", "telegram_users");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'tasks'
                  AND column_name = 'version'
                  AND is_nullable = 'NO'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void returnsOnlyCurrentUsersActiveTasks() {
        Task todo = taskService.createTask(command(
                2001L,
                3001L,
                "owner",
                "Todo task",
                null,
                TaskPriority.LOW,
                null
        ));
        Task inProgress = taskService.createTask(command(
                2001L,
                3001L,
                "owner",
                "In progress task",
                null,
                TaskPriority.MEDIUM,
                null
        ));
        Task completed = taskService.createTask(command(
                2001L,
                3001L,
                "owner",
                "Completed task",
                null,
                TaskPriority.HIGH,
                null
        ));
        taskService.createTask(command(
                2002L,
                3002L,
                "other_owner",
                "Another user's task",
                null,
                TaskPriority.HIGH,
                null
        ));
        entityManager.flush();
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'IN_PROGRESS' WHERE id = ?",
                inProgress.getId()
        );
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                completed.getId()
        );
        entityManager.clear();

        TaskPage result = taskService.getActiveTasks(2001L, 0);

        assertThat(result.tasks())
                .extracting(item -> item.id())
                .containsExactlyInAnyOrder(todo.getId(), inProgress.getId());
        assertThat(result.tasks())
                .extracting(item -> item.title())
                .doesNotContain("Completed task", "Another user's task");
        assertThat(result.totalTasks()).isEqualTo(2);
    }

    @Test
    void sortsDeadlinesAscendingThenCreationDescendingWithNullsLast() {
        Task laterDeadline = taskService.createTask(command(
                2101L, 3101L, null, "Later", null,
                TaskPriority.LOW, Instant.parse("2030-01-02T10:00:00Z")
        ));
        Task olderSameDeadline = taskService.createTask(command(
                2101L, 3101L, null, "Older same deadline", null,
                TaskPriority.LOW, Instant.parse("2030-01-01T10:00:00Z")
        ));
        Task newerSameDeadline = taskService.createTask(command(
                2101L, 3101L, null, "Newer same deadline", null,
                TaskPriority.LOW, Instant.parse("2030-01-01T10:00:00Z")
        ));
        Task noDeadline = taskService.createTask(command(
                2101L, 3101L, null, "No deadline", null,
                TaskPriority.LOW, null
        ));
        entityManager.flush();
        jdbcTemplate.update(
                "UPDATE tasks SET created_at = '2029-01-01T00:00:00Z' WHERE id = ?",
                olderSameDeadline.getId()
        );
        jdbcTemplate.update(
                "UPDATE tasks SET created_at = '2029-02-01T00:00:00Z' WHERE id = ?",
                newerSameDeadline.getId()
        );
        entityManager.clear();

        TaskPage result = taskService.getActiveTasks(2101L, 0);

        assertThat(result.tasks())
                .extracting(item -> item.id())
                .containsExactly(
                        newerSameDeadline.getId(),
                        olderSameDeadline.getId(),
                        laterDeadline.getId(),
                        noDeadline.getId()
                );
    }

    @Test
    void paginatesAtTenAndClampsOutOfRangePage() {
        for (int index = 1; index <= 12; index++) {
            taskService.createTask(command(
                    2201L,
                    3201L,
                    null,
                    "Task " + index,
                    null,
                    TaskPriority.LOW,
                    Instant.parse("2030-01-%02dT10:00:00Z".formatted(index))
            ));
        }
        entityManager.flush();
        entityManager.clear();

        TaskPage firstPage = taskService.getActiveTasks(2201L, 0);
        TaskPage secondPage = taskService.getActiveTasks(2201L, 1);
        TaskPage clampedPage = taskService.getActiveTasks(2201L, 999);

        assertThat(firstPage.tasks()).hasSize(10);
        assertThat(firstPage.pageNumber()).isZero();
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.totalTasks()).isEqualTo(12);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.tasks()).hasSize(2);
        assertThat(secondPage.pageNumber()).isEqualTo(1);
        assertThat(secondPage.totalTasks()).isEqualTo(12);
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();

        assertThat(clampedPage.pageNumber()).isEqualTo(1);
        assertThat(clampedPage.totalTasks()).isEqualTo(12);
        assertThat(clampedPage.tasks())
                .extracting(item -> item.id())
                .containsExactlyElementsOf(
                        secondPage.tasks().stream().map(item -> item.id()).toList()
                );
    }

    @Test
    void normalizesOutOfRangePageWhenUserHasNoTasks() {
        TaskPage result = taskService.getActiveTasks(999999L, 500);

        assertThat(result.tasks()).isEmpty();
        assertThat(result.pageNumber()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.totalTasks()).isZero();
    }

    @Test
    void deletesOnlyTaskOwnedByRequestingTelegramUser() {
        Task owned = taskService.createTask(command(
                2301L, 3301L, null, "Owned task", null,
                TaskPriority.MEDIUM, null
        ));
        Task otherUsersTask = taskService.createTask(command(
                2302L, 3302L, null, "Other task", null,
                TaskPriority.HIGH, null
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(taskService.findOwnedTaskTitle(2301L, owned.getId()))
                .contains("Owned task");
        assertThat(taskService.findOwnedTaskTitle(2301L, otherUsersTask.getId()))
                .isEmpty();
        assertThat(taskService.deleteTask(2301L, otherUsersTask.getId())).isFalse();
        assertThat(taskService.deleteTask(2301L, owned.getId())).isTrue();
        assertThat(taskService.deleteTask(2301L, owned.getId())).isFalse();
        entityManager.flush();
        entityManager.clear();

        assertThat(taskRepository.findById(owned.getId())).isEmpty();
        assertThat(taskRepository.findById(otherUsersTask.getId())).isPresent();
        assertThat(taskService.getActiveTasks(2301L, 0).tasks()).isEmpty();
    }

    @Test
    void bulkDeletesAllStatusesForOwnerButPreservesOtherUserAndTelegramUser() {
        Task todo = taskService.createTask(command(
                2401L, 3401L, null, "Todo", null,
                TaskPriority.LOW, null
        ));
        Task inProgress = taskService.createTask(command(
                2401L, 3401L, null, "In progress", null,
                TaskPriority.MEDIUM, null
        ));
        Task completed = taskService.createTask(command(
                2401L, 3401L, null, "Completed", null,
                TaskPriority.HIGH, null
        ));
        Task otherUsersTask = taskService.createTask(command(
                2402L, 3402L, null, "Other user's task", null,
                TaskPriority.HIGH, null
        ));
        entityManager.flush();
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'IN_PROGRESS' WHERE id = ?",
                inProgress.getId()
        );
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                completed.getId()
        );
        entityManager.clear();

        int deleted = taskService.deleteAllTasks(2401L);
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(3);
        assertThat(taskRepository.findAllByTelegramUserTelegramUserIdOrderByCreatedAtDesc(2401L))
                .isEmpty();
        assertThat(taskRepository.findById(otherUsersTask.getId())).isPresent();
        assertThat(telegramUserRepository.findByTelegramUserId(2401L)).isPresent();
        assertThat(telegramUserRepository.findByTelegramUserId(2402L)).isPresent();
        assertThat(taskRepository.findAllById(
                List.of(todo.getId(), inProgress.getId(), completed.getId())
        )).isEmpty();
    }

    @Test
    void bulkDeleteReturnsZeroForUserWithoutTasks() {
        assertThat(taskService.deleteAllTasks(999999L)).isZero();
    }

    @Test
    void readsOwnedTaskDetailsAndEnforcesStatusTransitions() {
        Task task = taskService.createTask(command(
                2501L, 3501L, null, "Transition task", "Full description",
                TaskPriority.HIGH, Instant.parse("2030-01-01T10:00:00Z")
        ));
        Task otherUsersTask = taskService.createTask(command(
                2502L, 3502L, null, "Foreign task", null,
                TaskPriority.LOW, null
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(taskService.getTaskDetails(2501L, task.getId()))
                .get()
                .satisfies(details -> {
                    assertThat(details.title()).isEqualTo("Transition task");
                    assertThat(details.description()).isEqualTo("Full description");
                    assertThat(details.status()).isEqualTo(TaskStatus.TODO);
                });
        assertThat(taskService.getTaskDetails(2501L, otherUsersTask.getId())).isEmpty();
        assertThat(taskService.startTask(2501L, otherUsersTask.getId()))
                .isEqualTo(TaskTransitionResult.NOT_FOUND);

        assertThat(taskService.startTask(2501L, task.getId()))
                .isEqualTo(TaskTransitionResult.SUCCESS);
        entityManager.flush();
        entityManager.clear();
        Task started = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(started.getCompletedAt()).isNull();
        assertThat(started.getVersion()).isEqualTo(1);
        assertThat(taskService.startTask(2501L, task.getId()))
                .isEqualTo(TaskTransitionResult.ALREADY_IN_PROGRESS);

        Instant beforeCompletion = Instant.now();
        assertThat(taskService.completeTask(2501L, task.getId()))
                .isEqualTo(TaskTransitionResult.SUCCESS);
        entityManager.flush();
        entityManager.clear();
        Task completed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isAfterOrEqualTo(beforeCompletion);
        assertThat(completed.getVersion()).isEqualTo(2);
        assertThat(taskService.completeTask(2501L, task.getId()))
                .isEqualTo(TaskTransitionResult.ALREADY_COMPLETED);
        assertThat(taskService.startTask(2501L, task.getId()))
                .isEqualTo(TaskTransitionResult.ALREADY_COMPLETED);
    }

    @Test
    void completesTodoTaskDirectlyAndSetsCompletedAt() {
        Task task = taskService.createTask(command(
                2601L, 3601L, null, "Direct completion", null,
                TaskPriority.MEDIUM, null
        ));
        entityManager.flush();
        entityManager.clear();

        Instant beforeCompletion = Instant.now();
        assertThat(taskService.completeTask(2601L, task.getId()))
                .isEqualTo(TaskTransitionResult.SUCCESS);
        entityManager.flush();
        entityManager.clear();

        Task completed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isAfterOrEqualTo(beforeCompletion);
    }

    @Test
    void detectsOptimisticLockConflict() {
        Task task = taskService.createTask(command(
                2701L, 3701L, null, "Concurrent task", null,
                TaskPriority.LOW, null
        ));
        entityManager.flush();

        jdbcTemplate.update(
                "UPDATE tasks SET version = version + 1 WHERE id = ?",
                task.getId()
        );
        assertThat(task.start()).isTrue();

        org.junit.jupiter.api.Assertions.assertThrows(
                OptimisticLockException.class,
                entityManager::flush
        );
    }

    private CreateTaskCommand command(
            Long telegramUserId,
            Long chatId,
            String username,
            String title,
            String description,
            TaskPriority priority,
            Instant deadline
    ) {
        return new CreateTaskCommand(
                new TelegramUserData(
                        telegramUserId,
                        chatId,
                        username,
                        "First",
                        "Last"
                ),
                title,
                description,
                priority,
                deadline
        );
    }
}

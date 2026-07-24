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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(TaskService.class)
@Testcontainers
class TaskServiceIntegrationIT {

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

        assertThat(appliedMigrations).isEqualTo(1);
        assertThat(tables).containsExactly("tasks", "telegram_users");
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
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.tasks()).hasSize(2);
        assertThat(secondPage.pageNumber()).isEqualTo(1);
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();

        assertThat(clampedPage.pageNumber()).isEqualTo(1);
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

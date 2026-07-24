package com.github.taskmasterbot.bot;

import com.github.taskmasterbot.config.ApplicationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

@Service
public class TaskConversationService {

    static final String TITLE_PROMPT = "Enter the task title:";
    static final String DESCRIPTION_PROMPT = "Enter a description or send /skip:";
    static final String PRIORITY_PROMPT = "Select a priority:";
    static final String DEADLINE_PROMPT = """
            Enter the deadline in format YYYY-MM-DD HH:mm
            or send /skip:""";
    static final String CANCEL_RESPONSE = "Task creation cancelled.";

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final DateTimeFormatter DEADLINE_FORMATTER = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private final InMemoryConversationStore store;
    private final ApplicationProperties properties;
    private final Clock clock;

    public TaskConversationService(
            InMemoryConversationStore store,
            ApplicationProperties properties,
            Clock clock
    ) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public ConversationReply start(Long userId) {
        store.start(userId);
        return ConversationReply.text(TITLE_PROMPT);
    }

    public ConversationReply cancel(Long userId) {
        store.clear(userId);
        return ConversationReply.mainMenu(CANCEL_RESPONSE);
    }

    public void reset(Long userId) {
        store.clear(userId);
    }

    public boolean isActive(Long userId) {
        return store.isActive(userId);
    }

    public ConversationReply handle(Long userId, String input) {
        ConversationSession session = store.get(userId);
        if (session == null) {
            throw new IllegalStateException("No active conversation for Telegram user");
        }

        synchronized (session) {
            return switch (session.getState()) {
                case WAITING_FOR_TITLE -> handleTitle(session, input);
                case WAITING_FOR_DESCRIPTION -> handleDescription(session, input);
                case WAITING_FOR_PRIORITY -> handlePriority(session, input);
                case WAITING_FOR_DEADLINE -> handleDeadline(userId, session, input);
                case IDLE -> throw new IllegalStateException("Idle sessions must not be stored");
            };
        }
    }

    private ConversationReply handleTitle(ConversationSession session, String input) {
        String title = input.trim();
        if (title.isBlank()) {
            return ConversationReply.text("Title cannot be blank. Enter the task title:");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return ConversationReply.text(
                    "Title must be 255 characters or fewer. Enter the task title:"
            );
        }

        session.getDraft().setTitle(title);
        session.setState(ConversationState.WAITING_FOR_DESCRIPTION);
        return ConversationReply.text(DESCRIPTION_PROMPT);
    }

    private ConversationReply handleDescription(ConversationSession session, String input) {
        if (!"/skip".equals(input.trim()) && input.length() > MAX_DESCRIPTION_LENGTH) {
            return ConversationReply.text(
                    "Description must be 2000 characters or fewer. "
                            + "Enter a description or send /skip:"
            );
        }

        session.getDraft().setDescription("/skip".equals(input.trim()) ? null : input);
        session.setState(ConversationState.WAITING_FOR_PRIORITY);
        return ConversationReply.priority(PRIORITY_PROMPT);
    }

    private ConversationReply handlePriority(ConversationSession session, String input) {
        TaskPriority priority = switch (input.trim()) {
            case PriorityKeyboard.LOW_BUTTON -> TaskPriority.LOW;
            case PriorityKeyboard.MEDIUM_BUTTON -> TaskPriority.MEDIUM;
            case PriorityKeyboard.HIGH_BUTTON -> TaskPriority.HIGH;
            default -> null;
        };

        if (priority == null) {
            return ConversationReply.priority(
                    "Please select one of the priority buttons: "
                            + PriorityKeyboard.LOW_BUTTON + ", "
                            + PriorityKeyboard.MEDIUM_BUTTON + ", or "
                            + PriorityKeyboard.HIGH_BUTTON + "."
            );
        }

        session.getDraft().setPriority(priority);
        session.setState(ConversationState.WAITING_FOR_DEADLINE);
        return ConversationReply.removeKeyboard(DEADLINE_PROMPT);
    }

    private ConversationReply handleDeadline(
            Long userId,
            ConversationSession session,
            String input
    ) {
        String normalizedInput = input.trim();
        if (!"/skip".equals(normalizedInput)) {
            ZonedDateTime deadline;
            try {
                LocalDateTime localDeadline = LocalDateTime.parse(
                        normalizedInput,
                        DEADLINE_FORMATTER
                );
                deadline = localDeadline.atZone(properties.timeZone());
            } catch (DateTimeParseException exception) {
                return ConversationReply.text(
                        "Invalid deadline. Use format YYYY-MM-DD HH:mm or send /skip:"
                );
            }

            if (!deadline.isAfter(ZonedDateTime.now(clock))) {
                return ConversationReply.text(
                        "Deadline must be in the future. "
                                + "Use format YYYY-MM-DD HH:mm or send /skip:"
                );
            }
            session.getDraft().setDeadline(deadline);
        }

        String summary = createSummary(session.getDraft());
        store.clear(userId);
        return ConversationReply.mainMenu(summary);
    }

    private String createSummary(TaskDraft draft) {
        String description = draft.getDescription() == null ? "—" : draft.getDescription();
        String deadline = draft.getDeadline() == null
                ? "—"
                : DEADLINE_FORMATTER.format(draft.getDeadline());

        return """
                ✅ Task draft created

                Title: %s
                Description: %s
                Priority: %s
                Deadline: %s"""
                .formatted(
                        draft.getTitle(),
                        description,
                        draft.getPriority(),
                        deadline
                );
    }
}

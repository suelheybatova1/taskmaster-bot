package com.github.taskmasterbot.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "task_reminders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_reminders_task_type",
                columnNames = {"task_id", "reminder_type"}
        )
)
public class TaskReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 32)
    private ReminderType reminderType;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReminderStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected TaskReminder() {
    }

    public TaskReminder(Task task, ReminderType reminderType, Instant scheduledFor) {
        this.task = task;
        this.reminderType = reminderType;
        this.scheduledFor = scheduledFor;
        this.nextAttemptAt = scheduledFor;
        this.status = ReminderStatus.PENDING;
    }

    public void claim(Instant leaseUntil) {
        status = ReminderStatus.PROCESSING;
        nextAttemptAt = leaseUntil;
    }

    public void markSent(Instant now) {
        if (status != ReminderStatus.PROCESSING) {
            return;
        }
        attemptCount++;
        sentAt = now;
        lastErrorMessage = null;
        status = ReminderStatus.SENT;
    }

    public void markFailed(Instant retryAt, int maxAttempts, String error) {
        if (status != ReminderStatus.PROCESSING) {
            return;
        }
        attemptCount++;
        lastErrorMessage = error == null ? null : error.substring(0, Math.min(500, error.length()));
        nextAttemptAt = retryAt;
        status = attemptCount >= maxAttempts ? ReminderStatus.FAILED : ReminderStatus.PENDING;
    }

    public void cancel() {
        if (status == ReminderStatus.PENDING || status == ReminderStatus.PROCESSING) {
            status = ReminderStatus.CANCELLED;
        }
    }

    public Long getId() { return id; }
    public Task getTask() { return task; }
    public ReminderType getReminderType() { return reminderType; }
    public Instant getScheduledFor() { return scheduledFor; }
    public Instant getSentAt() { return sentAt; }
    public ReminderStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}

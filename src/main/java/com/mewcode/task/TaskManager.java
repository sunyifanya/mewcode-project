package com.mewcode.task;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory task manager for tracking background sub-agent tasks.
 * Accumulates notifications that the parent agent can drain between turns.
 *
 * <p>Thread-safe: all public methods are synchronized.</p>
 */
public class TaskManager {

    private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();

    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    /** Optional: called with task ID when a task completes. */
    private Consumer<String> onTaskComplete;

    public TaskManager() {}

    public void setOnTaskComplete(Consumer<String> onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    // ── Creation ──────────────────────────────────────────────────────────

    /**
     * Create a new task entry in PENDING state.
     *
     * @param name optional human-readable name
     * @return the generated task ID
     */
    public synchronized String createTask(String name) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new BackgroundTask(id, name));
        return id;
    }

    // ── State transitions ─────────────────────────────────────────────────

    public synchronized void setRunning(String id, Thread thread) {
        BackgroundTask backgroundTask = tasks.get(id);
        if (backgroundTask != null) {
            backgroundTask.setStatus(BackgroundTask.Status.RUNNING);
            backgroundTask.setThread(thread);
        }
    }

    public synchronized void setCompleted(String id, String output) {
        BackgroundTask backgroundTask = tasks.get(id);
        if (backgroundTask != null) {
            backgroundTask.setStatus(BackgroundTask.Status.COMPLETED);
            backgroundTask.setEndTime(java.time.Instant.now());
            backgroundTask.setResult(output);
            notifications.add(new TaskNotification(id, backgroundTask.getName(),
                    BackgroundTask.Status.COMPLETED, output));
            if (onTaskComplete != null) onTaskComplete.accept(id);
        }
    }

    public synchronized void setFailed(String id, String error) {
        BackgroundTask backgroundTask = tasks.get(id);
        if (backgroundTask != null) {
            backgroundTask.setStatus(BackgroundTask.Status.FAILED);
            backgroundTask.setEndTime(java.time.Instant.now());
            backgroundTask.setError(error);
            notifications.add(new TaskNotification(id, backgroundTask.getName(),
                    BackgroundTask.Status.FAILED, error));
            if (onTaskComplete != null) onTaskComplete.accept(id);
        }
    }

    public synchronized void cancelTask(String id) {
        BackgroundTask backgroundTask = tasks.get(id);
        if (backgroundTask != null) {
            if (backgroundTask.getStatus() == BackgroundTask.Status.RUNNING) {
                backgroundTask.setCancelFlag(true);
                Thread thread = backgroundTask.getThread();
                if (thread != null) {
                    thread.interrupt();
                }
            }
            backgroundTask.setStatus(BackgroundTask.Status.CANCELLED);
            backgroundTask.setEndTime(java.time.Instant.now());
            notifications.add(new TaskNotification(id, backgroundTask.getName(),
                    BackgroundTask.Status.CANCELLED, ""));
        }
    }

    public synchronized void incrementToolCount(String id, String toolName) {
        BackgroundTask t = tasks.get(id);
        if (t != null) {
            t.setToolCount(t.getToolCount() + 1);
            t.setLastActivity(toolName);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Drain and reset the notification queue. Called by AgentLoop
     * before each turn to inject completed-task notifications.
     */
    public synchronized List<TaskNotification> drainNotifications() {
        var result = new ArrayList<>(notifications);
        notifications.clear();
        return result;
    }

    /**
     * Get a specific task by ID.
     */
    public synchronized BackgroundTask getTask(String id) {
        return tasks.get(id);
    }

    /**
     * List all non-terminal tasks.
     */
    public synchronized List<BackgroundTask> listTasks() {
        return tasks.values().stream()
                .filter(backgroundTask -> !backgroundTask.isTerminal())
                .toList();
    }

    /**
     * Find a task by its optional name. Returns the first match
     * that is still alive (RUNNING or COMPLETED).
     */
    public synchronized BackgroundTask findByName(String name) {
        if (name == null) return null;
        for (BackgroundTask backgroundTask : tasks.values()) {
            if (name.equals(backgroundTask.getName())
                    && (backgroundTask.getStatus() == BackgroundTask.Status.RUNNING
                        || backgroundTask.getStatus() == BackgroundTask.Status.COMPLETED)) {
                return backgroundTask;
            }
        }
        return null;
    }

    /**
     * Get all tasks (including terminal ones).
     */
    public synchronized List<BackgroundTask> allTasks() {
        return new ArrayList<>(tasks.values());
    }

    // ── Convenience ───────────────────────────────────────────────────────

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}

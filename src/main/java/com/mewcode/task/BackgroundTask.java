package com.mewcode.task;

import java.time.Instant;

/**
 * Tracks the lifecycle of a single background sub-agent task.
 */
public class BackgroundTask {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    private final String id;
    private final String name;
    private volatile Status status;
    private volatile String result;
    private volatile String error;
    private final Instant startTime;
    private volatile Instant endTime;
    private volatile boolean cancelFlag;
    private volatile int toolCount;
    private volatile String lastActivity;
    private volatile Thread thread;

    public BackgroundTask(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = Status.PENDING;
        this.startTime = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getName() { return name; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public boolean isCancelled() { return cancelFlag; }
    public int getToolCount() { return toolCount; }
    public String getLastActivity() { return lastActivity; }
    public Thread getThread() { return thread; }

    // ── Setters ───────────────────────────────────────────────────────────

    public void setStatus(Status status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public void setCancelFlag(boolean cancelFlag) { this.cancelFlag = cancelFlag; }
    public void setToolCount(int toolCount) { this.toolCount = toolCount; }
    public void setLastActivity(String lastActivity) { this.lastActivity = lastActivity; }
    public void setThread(Thread thread) { this.thread = thread; }

    // ── Derived ───────────────────────────────────────────────────────────

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    /**
     * Elapsed wall-clock time in seconds.
     */
    public double elapsedSeconds() {
        Instant end = endTime != null ? endTime : Instant.now();
        return (end.toEpochMilli() - startTime.toEpochMilli()) / 1000.0;
    }

    @Override
    public String toString() {
        return "BackgroundTask{id=" + id + ", name=" + name + ", status=" + status + "}";
    }
}

package com.mewcode.task;

/**
 * Notification produced when a background task reaches a terminal state.
 */
public record TaskNotification(
        String taskId,
        String name,
        BackgroundTask.Status status,
        String output
) {
    /**
     * Format this notification as a {@code <task-notification>} block
     * for injection into the parent conversation.
     */
    public String format() {
        var sb = new StringBuilder();
        sb.append("<task-notification>\n");
        sb.append("Task ").append(taskId);
        if (name != null && !name.isEmpty()) {
            sb.append(" (name=\"").append(name).append("\")");
        }
        sb.append(": ").append(status.name().toLowerCase()).append("\n");
        if (output != null && !output.isEmpty()) {
            sb.append("Result: ").append(output).append("\n");
        }
        sb.append("</task-notification>");
        return sb.toString();
    }
}

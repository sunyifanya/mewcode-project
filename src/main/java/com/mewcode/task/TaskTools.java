package com.mewcode.task;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Four MCP-style tool implementations that expose the {@link TaskList} to
 * the LLM agent: create, get, list, and update.
 * <p>
 * All tools return {@code shouldDefer() == true} and category {@code COMMAND}.
 */
public final class TaskTools {

    private TaskTools() {} // utility class

    // ------------------------------------------------------------------
    // TaskCreate
    // ------------------------------------------------------------------

    public static class TaskCreateTool implements Tool {

        private final TaskList taskList;

        public TaskCreateTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String getName() {
            return "TaskCreate";
        }

        @Override
        public String getDescription() {
            return "Create a new task to track work. Use this to break complex work "
                    + "into smaller, trackable steps before starting implementation.";
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.COMMAND;
        }

        @Override
        public boolean shouldDefer() {
            return true;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subject", Map.of("type", "string",
                                    "description", "A brief title for the task"),
                            "description", Map.of("type", "string",
                                    "description", "What needs to be done"),
                            "activeForm", Map.of("type", "string",
                                    "description", "Present continuous form shown in spinner "
                                            + "when in_progress (e.g., \"Running tests\")"),
                            "metadata", Map.of("type", "object",
                                    "description", "Arbitrary metadata to attach to the task")
                    ),
                    "required", List.of("subject", "description")
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult execute(Map<String, Object> args) {
            String subject = asString(args, "subject");
            String desc = asString(args, "description");
            if (subject == null || subject.isEmpty() || desc == null || desc.isEmpty()) {
                return new ToolResult(false, "Error: subject and description are required");
            }

            String activeForm = asString(args, "activeForm");
            Map<String, Object> metadata = null;
            Object raw = args.get("metadata");
            if (raw instanceof Map<?, ?> m) {
                metadata = new LinkedHashMap<>();
                for (var entry : m.entrySet()) {
                    metadata.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            TaskList.Task task = taskList.create(subject, desc, activeForm, metadata);
            return new ToolResult(true,
                    "Task #" + task.getId() + " created successfully: " + task.getSubject());
        }
    }

    // ------------------------------------------------------------------
    // TaskGet
    // ------------------------------------------------------------------

    public static class TaskGetTool implements Tool {

        private final TaskList taskList;

        public TaskGetTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String getName() {
            return "TaskGet";
        }

        @Override
        public String getDescription() {
            return "Get the details of a specific task by its ID.";
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.COMMAND;
        }

        @Override
        public boolean shouldDefer() {
            return true;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "taskId", Map.of("type", "string",
                                    "description", "The ID of the task to retrieve")
                    ),
                    "required", List.of("taskId")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = asString(args, "taskId");
            if (taskId == null || taskId.isEmpty()) {
                return new ToolResult(false, "Error: taskId is required");
            }

            Optional<TaskList.Task> opt = taskList.get(taskId);
            if (opt.isEmpty()) {
                return new ToolResult(true, "Task #" + taskId + " not found");
            }

            TaskList.Task task = opt.get();
            var sb = new StringBuilder();
            sb.append("Task #").append(task.getId()).append('\n');
            sb.append("Subject: ").append(task.getSubject()).append('\n');
            sb.append("Status: ").append(task.getStatus()).append('\n');
            sb.append("Description: ").append(task.getDescription()).append('\n');
            if (task.getBlocks() != null && !task.getBlocks().isEmpty()) {
                sb.append("Blocks: ").append(String.join(", ", task.getBlocks())).append('\n');
            }
            if (task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()) {
                sb.append("Blocked by: ").append(String.join(", ", task.getBlockedBy())).append('\n');
            }
            if (task.getOwner() != null && !task.getOwner().isEmpty()) {
                sb.append("Owner: ").append(task.getOwner()).append('\n');
            }
            return new ToolResult(true, sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // TaskList
    // ------------------------------------------------------------------

    public static class TaskListTool implements Tool {

        private final TaskList taskList;

        public TaskListTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String getName() {
            return "TaskList";
        }

        @Override
        public String getDescription() {
            return "List all tasks in the current task list. Shows ID, status, subject, "
                    + "and blocking info.";
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.COMMAND;
        }

        @Override
        public boolean shouldDefer() {
            return true;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of()
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            List<TaskList.Task> tasks = taskList.list();
            if (tasks.isEmpty()) {
                return new ToolResult(true, "No tasks found.");
            }

            // Build a set of completed task IDs so we can filter active blockers
            var completedIds = new LinkedHashSet<String>();
            for (TaskList.Task task : tasks) {
                if (TaskList.Status.COMPLETED.value().equals(task.getStatus())) {
                    completedIds.add(task.getId());
                }
            }

            var sb = new StringBuilder();
            for (TaskList.Task task : tasks) {
                sb.append('#').append(task.getId())
                        .append(" [").append(task.getStatus()).append("] ")
                        .append(task.getSubject());

                if (task.getOwner() != null && !task.getOwner().isEmpty()) {
                    sb.append(" (owner: ").append(task.getOwner()).append(')');
                }

                // Show only active (non-completed) blockers
                if (task.getBlockedBy() != null) {
                    List<String> activeBlockers = new ArrayList<>();
                    for (String b : task.getBlockedBy()) {
                        if (!completedIds.contains(b)) {
                            activeBlockers.add(b);
                        }
                    }
                    if (!activeBlockers.isEmpty()) {
                        sb.append(" [blocked by: ")
                                .append(String.join(", ", activeBlockers))
                                .append(']');
                    }
                }
                sb.append('\n');
            }
            return new ToolResult(true, sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // TaskUpdate
    // ------------------------------------------------------------------

    public static class TaskUpdateTool implements Tool {

        private final TaskList taskList;

        public TaskUpdateTool(TaskList taskList) {
            this.taskList = taskList;
        }

        @Override
        public String getName() {
            return "TaskUpdate";
        }

        @Override
        public String getDescription() {
            return "Update a task's status, subject, description, or dependencies. "
                    + "Set status to \"in_progress\" when starting work, \"completed\" when done. "
                    + "Set status to \"deleted\" to remove a task.";
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.COMMAND;
        }

        @Override
        public boolean shouldDefer() {
            return true;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("taskId", Map.of("type", "string",
                    "description", "The ID of the task to update"));
            props.put("subject", Map.of("type", "string",
                    "description", "New subject for the task"));
            props.put("description", Map.of("type", "string",
                    "description", "New description for the task"));
            props.put("activeForm", Map.of("type", "string",
                    "description", "Present continuous form shown in spinner when in_progress"));
            props.put("status", Map.of("type", "string",
                    "enum", List.of("pending", "in_progress", "completed", "deleted"),
                    "description", "New status for the task"));
            props.put("addBlocks", Map.of("type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Task IDs that this task blocks"));
            props.put("addBlockedBy", Map.of("type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Task IDs that block this task"));
            props.put("owner", Map.of("type", "string",
                    "description", "New owner for the task"));
            props.put("metadata", Map.of("type", "object",
                    "description", "Metadata keys to merge. Set a key to null to delete it."));

            return Map.of(
                    "type", "object",
                    "properties", props,
                    "required", List.of("taskId")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = asString(args, "taskId");
            if (taskId == null || taskId.isEmpty()) {
                return new ToolResult(false, "Error: taskId is required");
            }

            Optional<TaskList.UpdateResult> result = taskList.update(taskId, args);
            if (result.isEmpty()) {
                return new ToolResult(false, "Error: task #" + taskId + " not found");
            }

            TaskList.UpdateResult ur = result.get();
            if (ur.changed().isEmpty()) {
                return new ToolResult(true, "Task #" + taskId + ": no changes applied");
            }
            return new ToolResult(true,
                    "Task #" + taskId + " updated: " + String.join(", ", ur.changed()));
        }
    }

    // ---- shared helpers ----

    private static String asString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}

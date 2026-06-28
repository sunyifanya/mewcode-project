package com.mewcode.tool.impl;

import com.mewcode.task.TaskManager;
import com.mewcode.task.BackgroundTask;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Get full details of a specific background task.
 */
public class TaskGetTool implements Tool {

    private final TaskManager taskManager;

    public TaskGetTool(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() { return "TaskGet"; }

    @Override
    public String getDescription() {
        return "获取指定后台任务的完整状态，含 result 和 error（如有）。参数: task_id。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", Map.of(
                "type", "string",
                "description", "要查询的任务 ID"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task_id"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (taskManager == null) {
            return new ToolResult(false, "没有任务管理器", "NO_TASK_MANAGER");
        }

        String taskId = getString(params, "task_id");
        if (taskId == null || taskId.isEmpty()) {
            return new ToolResult(false, "缺少 task_id 参数", "MISSING_PARAM");
        }

        BackgroundTask task = taskManager.getTask(taskId);
        if (task == null) {
            return new ToolResult(false, "找不到任务: " + taskId, "NOT_FOUND");
        }

        var sb = new StringBuilder();
        sb.append("任务 ").append(task.getId()).append(":\n");
        sb.append("  名称: ").append(task.getName() != null ? task.getName() : "(未命名)").append("\n");
        sb.append("  状态: ").append(task.getStatus().name().toLowerCase()).append("\n");
        sb.append("  工具调用次数: ").append(task.getToolCount()).append("\n");
        sb.append("  耗时: ").append(String.format("%.1f", task.elapsedSeconds())).append("s\n");

        if (task.getResult() != null && !task.getResult().isEmpty()) {
            sb.append("  结果:\n").append(task.getResult()).append("\n");
        }
        if (task.getError() != null && !task.getError().isEmpty()) {
            sb.append("  错误: ").append(task.getError()).append("\n");
        }

        return new ToolResult(true, sb.toString());
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}

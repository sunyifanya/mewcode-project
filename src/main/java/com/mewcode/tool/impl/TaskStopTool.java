package com.mewcode.tool.impl;

import com.mewcode.task.TaskManager;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cancel a running background task.
 */
public class TaskStopTool implements Tool {

    private final TaskManager taskManager;

    public TaskStopTool(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() { return "TaskStop"; }

    @Override
    public String getDescription() {
        return "取消正在运行的后台子 Agent 任务。参数: task_id。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task_id", Map.of(
                "type", "string",
                "description", "要取消的任务 ID"
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

        taskManager.cancelTask(taskId);
        return new ToolResult(true, "{\"status\": \"cancellation_requested\", \"task_id\": \"" + taskId + "\"}");
    }

    @Override
    public ToolCategory category() { return ToolCategory.COMMAND; }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}

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
 * List all non-terminal background tasks.
 */
public class TaskListTool implements Tool {

    private final TaskManager taskManager;

    public TaskListTool(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() { return "TaskList"; }

    @Override
    public String getDescription() {
        return "列出所有当前活动的后台子 Agent 任务，含 id/name/status/tool_count/last_activity。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (taskManager == null) {
            return new ToolResult(false, "没有任务管理器（subagent 功能未启用）", "NO_TASK_MANAGER");
        }

        List<BackgroundTask> tasks = taskManager.listTasks();
        if (tasks.isEmpty()) {
            return new ToolResult(true, "当前没有活动的后台任务。");
        }

        var sb = new StringBuilder();
        sb.append("当前活动的后台任务 (").append(tasks.size()).append("):\n");
        for (BackgroundTask t : tasks) {
            sb.append("- ").append(t.getId());
            if (t.getName() != null && !t.getName().isEmpty()) {
                sb.append(" (").append(t.getName()).append(")");
            }
            sb.append(": ").append(t.getStatus().name().toLowerCase());
            sb.append(", 工具调用: ").append(t.getToolCount());
            if (t.getLastActivity() != null) {
                sb.append(", 最近: ").append(t.getLastActivity());
            }
            sb.append("\n");
        }
        return new ToolResult(true, sb.toString());
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }
}

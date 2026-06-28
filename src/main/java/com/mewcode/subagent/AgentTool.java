package com.mewcode.subagent;

import com.mewcode.agent.AgentLoop;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.provider.LLMProvider;
import com.mewcode.task.TaskManager;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.task.BackgroundTask;

import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A tool that launches a sub-agent to handle a focused task.
 */
public class AgentTool implements Tool {

    private static final long AUTO_BACKGROUND_MS = 120_000; // 120 seconds
    private static final int BG_TASK_OUTPUT_TRUNCATE = 2000;

    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final AgentCatalog catalog;
    private final TaskManager taskManager;
    private final PermissionChecker permissionChecker;
    private final int globalMaxTurns;
    private final int streamTimeoutSeconds;
    private final boolean backgroundEnabled;
    private final String workingDirectory;

    /** Parent conversation — used for Fork path. */
    private ConversationManager parentConversation;

    /** Parent's replacement state — cloned for Fork cache stability. */
    private com.mewcode.toolresult.ContentReplacementState parentReplacementState;

    public AgentTool(LLMProvider provider, ToolRegistry toolRegistry,
                     AgentCatalog catalog, TaskManager taskManager,
                     PermissionChecker permissionChecker,
                     int globalMaxTurns, int streamTimeoutSeconds,
                     boolean backgroundEnabled, String workingDirectory) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.catalog = catalog;
        this.taskManager = taskManager;
        this.permissionChecker = permissionChecker;
        this.globalMaxTurns = globalMaxTurns;
        this.streamTimeoutSeconds = streamTimeoutSeconds;
        this.backgroundEnabled = backgroundEnabled;
        this.workingDirectory = workingDirectory;
    }

    // ── Context setters (called per-turn) ─────────────────────────────────

    public void setParentConversation(ConversationManager parentConversation) {
        this.parentConversation = parentConversation;
    }

    public void setParentReplacementState(com.mewcode.toolresult.ContentReplacementState state) {
        this.parentReplacementState = state;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    // ── Tool interface ────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "Agent";
    }

    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("启动一个子 Agent 处理独立任务。每个子 Agent 拥有独立的上下文和受限的工具集。\n\n");
        sb.append("适用场景：研究代码、实现组件、代码审查等需要专注独立分析的任务。\n\n");
        sb.append("可用 Agent 类型：");

        List<String> names = catalog.listNames();
        if (names.isEmpty()) {
            sb.append("\n- general-purpose: 全工具访问，适合多步骤任务（默认）");
            sb.append("\n- explore: 只读搜索 Agent（haiku 模型，低成本快速扫描）");
            sb.append("\n- plan: 只读架构规划 Agent");
        } else {
            for (String name : names) {
                SubAgentSpec spec = catalog.resolve(name);
                if (spec != null) {
                    sb.append("\n- ").append(name).append(": ").append(spec.description());
                }
            }
        }

        sb.append("\n\n不指定 subagent_type 时走 Fork 路径，继承父对话历史（自动后台执行）。");
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        List<String> agentTypes = catalog.listNames();
        if (agentTypes.isEmpty()) {
            agentTypes = List.of("general-purpose", "explore", "plan");
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", Map.of(
                "type", "string",
                "description", "3-5 词任务简述，供 UI 进度展示"
        ));
        props.put("prompt", Map.of(
                "type", "string",
                "description", "交给子 Agent 的详细任务指令。子 Agent 没有当前对话上下文，需写清楚。"
        ));
        props.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "预定义角色名。留空走 Fork 路径（继承父对话历史）。"
        ));
        props.put("model", Map.of(
                "type", "string",
                "enum", List.of("haiku", "sonnet", "opus", "inherit"),
                "description", "覆盖模型选择。默认使用 Agent 定义中的 model。"
        ));
        props.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "强制后台启动。Fork 路径忽略此字段（无条件后台）。"
        ));
        props.put("name", Map.of(
                "type", "string",
                "description", "给本次启动的子 Agent 命名，供后续 SendMessage 使用。"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("description", "prompt"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String description = getString(args, "description");
        String prompt = getString(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return new ToolResult(false,
                    "Error: description 和 prompt 参数均为必填", "MISSING_PARAM");
        }

        String subagentType = getString(args, "subagent_type");
        String modelOverride = getString(args, "model");
        String name = getString(args, "name");
        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

        // ── Fork path (no subagent_type) ──────────────────────────────────
        if (subagentType == null || subagentType.isEmpty()) {
            return executeFork(description, prompt, modelOverride, name);
        }

        // ── Definition-based path ─────────────────────────────────────────
        SubAgentSpec spec = catalog.resolve(subagentType);
        if (spec == null) {
            String available = String.join(", ", catalog.listNames());
            return new ToolResult(false,
                    "未知 subagent_type: '" + subagentType + "'。可用类型: " + available,
                    "UNKNOWN_AGENT_TYPE");
        }

        // Determine execution mode
        if (spec.background() || runInBackground) {
            if (!backgroundEnabled) {
                return new ToolResult(false,
                        "后台执行已禁用（配置 subagent.background.enabled: false）",
                        "BACKGROUND_DISABLED");
            }
            return executeBackground(spec, description, prompt, modelOverride, name);
        }

        return executeSync(spec, description, prompt, modelOverride);
    }

    // ── Fork execution ────────────────────────────────────────────────────

    private ToolResult executeFork(String description, String prompt,
                                    String modelOverride, String name) {
        if (!backgroundEnabled) {
            return new ToolResult(false,
                    "后台执行已禁用。Fork 路径需要后台执行，当前配置 subagent.background.enabled: false",
                    "BACKGROUND_DISABLED");
        }

        if (parentConversation == null) {
            return new ToolResult(false,
                    "Error: Fork 需要父对话上下文（内部错误：parentConversation 未设置）",
                    "NO_PARENT_CONV");
        }

        // Nested fork detection
        if (ForkBuilder.containsForkBoilerplate(parentConversation)) {
            return new ToolResult(false,
                    "Fork 子 Agent 不能再启动 Agent（检测到嵌套 Fork）",
                    "NESTED_FORK");
        }

        // Build forked conversation
        ConversationManager forkedConversationManager = ForkBuilder.buildForkedConversation(parentConversation);
        String taskMessage = ForkBuilder.buildTaskMessage(prompt);
        forkedConversationManager.addUserMessage(taskMessage);

        // Fork uses the FORK spec (no tool restrictions, background, maxTurns=50)
        SubAgentSpec forkSpec = SubAgentSpec.FORK;

        // Build fork filter: inherits parent tools, only blocks Agent at runtime
        Predicate<String> forkFilter = ToolFilter.buildForkFilter();

        // Create sub-agent
        AgentLoop subAgent = createSubAgent(forkedConversationManager, forkSpec, forkFilter, modelOverride, name);

        if (parentReplacementState != null) {
            subAgent.setReplacementState(parentReplacementState.copy());
        }

        // Launch in background
        return launchBackgroundTask(subAgent, forkSpec, description, name);
    }

    // ── Definition-based sync ─────────────────────────────────────────────

    private ToolResult executeSync(SubAgentSpec spec, String description,
                                    String prompt, String modelOverride) {
        ConversationManager subConv = new ConversationManager();

        // Inject system prompt if specified
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            subConv.getMessagesMutable().add(
                    new com.mewcode.conversation.Message("user",
                            "<system-reminder>\n" + spec.systemPromptOverride() + "\n</system-reminder>"));
        }

        // Add task
        subConv.addUserMessage(prompt);

        // Build tool filter
        Predicate<String> toolFilter = ToolFilter.buildFilter(spec, false, false);

        // Create sub-agent
        AgentLoop subAgent = createSubAgent(subConv, spec, toolFilter, modelOverride, null);

        // Run with auto-background timeout
        return runWithTimeout(subAgent, spec, description, prompt, null);
    }

    // ── Definition-based background ───────────────────────────────────────

    private ToolResult executeBackground(SubAgentSpec spec, String description,
                                          String prompt, String modelOverride, String name) {
        ConversationManager subConv = new ConversationManager();

        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            subConv.getMessagesMutable().add(
                    new com.mewcode.conversation.Message("user",
                            "<system-reminder>\n" + spec.systemPromptOverride() + "\n</system-reminder>"));
        }

        subConv.addUserMessage(prompt);

        // Build tool filter — async definition-based
        boolean isFork = "_fork".equals(spec.name());
        Predicate<String> toolFilter = ToolFilter.buildFilter(spec, true, isFork);

        AgentLoop subAgent = createSubAgent(subConv, spec, toolFilter, modelOverride, name);

        return launchBackgroundTask(subAgent, spec, description, name);
    }

    // ── Sub-agent factory ─────────────────────────────────────────────────

    private AgentLoop createSubAgent(ConversationManager conv, SubAgentSpec spec,
                                      Predicate<String> toolFilter,
                                      String modelOverride, String name) {
        // Create event queue
        BlockingQueue<com.mewcode.agent.AgentEvent> eventQueue = new LinkedBlockingQueue<>(256);

        int effMaxTurns = spec.effectiveMaxTurns(globalMaxTurns);
        String permMode = spec.effectivePermissionMode();

        AgentLoop agent = new AgentLoop(
                provider,
                toolRegistry,
                conv,
                eventQueue,
                effMaxTurns,
                streamTimeoutSeconds,
                permissionChecker
        );
        agent.setPlanMode("plan".equals(permMode));
        agent.setWorkingDirectory(workingDirectory);
        agent.setToolFilter(toolFilter);

        // Configure as sub-agent
        String displayName = name != null ? name : spec.name();
        agent.configureAsSubAgent(displayName, spec.systemPromptOverride(),
                effMaxTurns, permMode);

        return agent;
    }

    // ── Background launch ─────────────────────────────────────────────────

    private ToolResult launchBackgroundTask(AgentLoop subAgent,
                                             SubAgentSpec spec, String description,
                                             String name) {
        String taskName = name != null ? name : description;
        String taskId = taskManager.createTask(taskName);

        Thread thread = Thread.startVirtualThread(() -> {
            taskManager.setRunning(taskId, Thread.currentThread());
            try {
                String result = subAgent.runToCompletion();
                taskManager.setCompleted(taskId, result);
            } catch (Exception e) {
                taskManager.setFailed(taskId, "Sub-agent error: " + e.getMessage());
            }
        });

        taskManager.setRunning(taskId, thread);

        return new ToolResult(true,
                "{\"task_id\": \"" + taskId + "\", \"status\": \"async_launched\", "
                        + "\"description\": \"" + escapeJson(description) + "\", "
                        + "\"hint\": \"任务完成后会自动通知你，无需轮询 TaskGet/TaskList\"}");
    }

    // ── Sync with timeout ─────────────────────────────────────────────────

    private ToolResult runWithTimeout(AgentLoop subAgent, SubAgentSpec spec,
                                       String description, String prompt, String name) {
        String taskName = name != null ? name : ("sync-" + spec.name());
        String taskId = taskManager.createTask(taskName);

        Thread agentThread = Thread.startVirtualThread(() -> {
            taskManager.setRunning(taskId, Thread.currentThread());
            try {
                String result = subAgent.runToCompletion();
                taskManager.setCompleted(taskId, result);
            } catch (Exception e) {
                taskManager.setFailed(taskId, "Sub-agent error: " + e.getMessage());
            }
        });
        taskManager.setRunning(taskId, agentThread);

        // Wait with timeout on the main thread
        try {
            long startTimeMillis = System.currentTimeMillis();
            while (true) {
                long diffTimeMillis = System.currentTimeMillis() - startTimeMillis;
                if (diffTimeMillis >= AUTO_BACKGROUND_MS) {
                    // Timed out — continues in background
                    return new ToolResult(true,
                            "{\"task_id\": \"" + taskId + "\", \"status\": \"timed_out_to_background\", "
                                    + "\"description\": \"" + escapeJson(description) + "\", "
                                    + "\"hint\": \"任务仍在后台运行，完成后会自动通知你\"}");
                }

                BackgroundTask task = taskManager.getTask(taskId);
                if (task != null && task.isTerminal()) {
                    if (task.getStatus() == BackgroundTask.Status.COMPLETED) {
                        return new ToolResult(true, task.getResult() != null ? task.getResult() : "");
                    } else {
                        return new ToolResult(false, task.getError() != null ? task.getError() : "Sub-agent failed",
                                "SUB_AGENT_FAILED");
                    }
                }

                Thread.sleep(200); // poll interval
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "Sub-agent was interrupted", "INTERRUPTED");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

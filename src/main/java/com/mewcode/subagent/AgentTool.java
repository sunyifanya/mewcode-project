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
import com.mewcode.worktree.AgentWorktree;
import com.mewcode.worktree.WorktreeChanges;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.teams.TeamManager;
import com.mewcode.teams.SpawnDispatcher;
import com.mewcode.teams.TeammateRunner;
import com.mewcode.teams.TeamTools;

import java.security.SecureRandom;
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
    private ContentReplacementState parentReplacementState;

    /** Optional: worktree manager for isolation mode. */
    private WorktreeManager worktreeManager;

    /** Optional: team manager for team_name support. */
    private TeamManager teamManager;

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

    public void setParentReplacementState(ContentReplacementState state) {
        this.parentReplacementState = state;
    }

    /** Optional: inject WorktreeManager for isolation support. */
    public void setWorktreeManager(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    /** Optional: inject TeamManager for team_name support. */
    public void setTeamManager(TeamManager teamManager) {
        this.teamManager = teamManager;
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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "3-5 词任务简述，供 UI 进度展示"
        ));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "交给子 Agent 的详细任务指令。子 Agent 没有当前对话上下文，需写清楚。"
        ));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "预定义角色名。留空走 Fork 路径（继承父对话历史）。"
        ));
        properties.put("model", Map.of(
                "type", "string",
                "enum", List.of("haiku", "sonnet", "opus", "inherit"),
                "description", "覆盖模型选择。默认使用 Agent 定义中的 model。"
        ));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "强制后台启动。Fork 路径忽略此字段（无条件后台）。"
        ));
        properties.put("name", Map.of(
                "type", "string",
                "description", "给本次启动的子 Agent 命名，供后续 SendMessage 使用。"
        ));
        properties.put("isolation", Map.of(
                "type", "string",
                "enum", List.of("worktree"),
                "description", "隔离模式。\"worktree\" 为子 Agent 创建独立的 git worktree，"
                        + "文件操作不会影响主 Agent 的工作目录。"
        ));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "REQUIRED when creating team members. Spawns the agent as a long-running "
                        + "teammate under this team (created via TeamCreate). Unlike regular sub-agents, team "
                        + "members persist after the lead returns and communicate via SendMessage. "
                        + "Without team_name the agent runs as a one-shot sub-agent that blocks and returns inline."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
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
        String isolation = getString(args, "isolation");
        String teamName = getString(args, "team_name");
        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

        // ── Team-member path: check BEFORE fork/subagent so team_name is never skipped ──
        if (teamName != null && !teamName.isEmpty() && teamManager != null) {
            SubAgentSpec spec = (subagentType != null && !subagentType.isEmpty())
                    ? catalog.resolve(subagentType) : catalog.resolve("general-purpose");
            if (spec == null) spec = SubAgentSpec.GENERAL_PURPOSE;
            return runAsTeammate(spec, teamName, description, prompt, modelOverride, isolation);
        }

        // ── Fork path (no subagent_type) ──────────────────────────────────
        if (subagentType == null || subagentType.isEmpty()) {
            return executeFork(description, prompt, modelOverride, name, isolation);
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
            return executeBackground(spec, description, prompt, modelOverride, name, isolation);
        }

        return executeSync(spec, description, prompt, modelOverride, isolation);
    }

    // ── Fork execution ────────────────────────────────────────────────────

    private ToolResult executeFork(String description, String prompt,
                                    String modelOverride, String name, String isolation) {
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

        // Fork uses the FORK spec (no tool restrictions, background, maxTurns=50)
        SubAgentSpec forkSpec = SubAgentSpec.FORK;

        // Build fork filter: inherits parent tools, only blocks Agent at runtime
        Predicate<String> forkFilter = ToolFilter.buildForkFilter();

        // Worktree isolation for fork
        AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            String slug = generateWorktreeSlug();
            try {
                wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
            } catch (Exception e) {
                return new ToolResult(false,
                        "Error creating agent worktree: " + e.getMessage(), "WORKTREE_FAILED");
            }
        }

        // Inject worktree notice into prompt
        String effectivePrompt = prompt;
        if (wtResult != null) {
            String notice = AgentWorktree.buildNotice(System.getProperty("user.dir"),
                    wtResult.worktreePath());
            effectivePrompt = notice + "\n\n" + effectivePrompt;
        }
        String taskMessage = ForkBuilder.buildTaskMessage(effectivePrompt);
        forkedConversationManager.addUserMessage(taskMessage);

        // Create sub-agent
        AgentLoop subAgent = createSubAgent(forkedConversationManager, forkSpec, forkFilter, modelOverride, name,
                wtResult != null ? wtResult.worktreePath() : workingDirectory);

        if (parentReplacementState != null) {
            subAgent.setReplacementState(parentReplacementState.copy());
        }

        // Launch in background with worktree cleanup
        return launchBackgroundTask(subAgent, forkSpec, description, name, wtResult);
    }

    // ── Definition-based sync ─────────────────────────────────────────────

    private ToolResult executeSync(SubAgentSpec spec, String description,
                                    String prompt, String modelOverride, String isolation) {
        // Worktree isolation via AgentWorktree API
        AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            String slug = generateWorktreeSlug();
            try {
                wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
            } catch (Exception e) {
                return new ToolResult(false,
                        "Error creating agent worktree: " + e.getMessage(), "WORKTREE_FAILED");
            }
        }

        String effectivePrompt = prompt;
        if (wtResult != null) {
            String notice = AgentWorktree.buildNotice(System.getProperty("user.dir"),
                    wtResult.worktreePath());
            effectivePrompt = notice + "\n\n" + effectivePrompt;
        }

        ConversationManager subConv = new ConversationManager();

        // Inject system prompt if specified
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) {
            subConv.getMessagesMutable().add(
                    new com.mewcode.conversation.Message("user",
                            "<system-reminder>\n" + spec.systemPromptOverride() + "\n</system-reminder>"));
        }

        // Execution directive: sub-agents must use tools, not just describe plans
        subConv.addSystemReminder("""
                你是执行 Agent。你有工具可以使用，必须调用工具来完成任务。
                不要只输出"我会这样做..."的计划描述——直接动手调用工具。
                不要询问确认，直接执行。
                完成后用一句话总结结果。""");

        // Add task
        subConv.addUserMessage(effectivePrompt);

        // Build tool filter
        Predicate<String> toolFilter = ToolFilter.buildFilter(spec, false, false);

        // Create sub-agent
        String workDir = wtResult != null ? wtResult.worktreePath() : workingDirectory;
        AgentLoop subAgent = createSubAgent(subConv, spec, toolFilter, modelOverride, null, workDir);

        // Run with auto-background timeout (and worktree cleanup on sync path)
        return runWithTimeout(subAgent, spec, description, effectivePrompt, null, wtResult);
    }

    // ── Definition-based background ───────────────────────────────────────

    private ToolResult executeBackground(SubAgentSpec subAgentSpec, String description,
                                          String prompt, String modelOverride,
                                          String name, String isolation) {
        // Worktree isolation via AgentWorktree API
        AgentWorktree.Result worktreeResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            String slug = generateWorktreeSlug();
            try {
                worktreeResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
            } catch (Exception e) {
                return new ToolResult(false,
                        "Error creating agent worktree: " + e.getMessage(), "WORKTREE_FAILED");
            }
        }

        String effectivePrompt = prompt;
        if (worktreeResult != null) {
            String notice = AgentWorktree.buildNotice(System.getProperty("user.dir"),
                    worktreeResult.worktreePath());
            effectivePrompt = notice + "\n\n" + effectivePrompt;
        }

        ConversationManager subConversationManager = new ConversationManager();

        if (subAgentSpec.systemPromptOverride() != null && !subAgentSpec.systemPromptOverride().isEmpty()) {
            subConversationManager.getMessagesMutable().add(
                    new com.mewcode.conversation.Message("user",
                            "<system-reminder>\n" + subAgentSpec.systemPromptOverride() + "\n</system-reminder>"));
        }

        // Execution directive: sub-agents must use tools, not just describe plans
        subConversationManager.addSystemReminder("""
                你是执行 Agent。你有工具可以使用，必须调用工具来完成任务。
                不要只输出"我会这样做..."的计划描述——直接动手调用工具。
                不要询问确认，直接执行。
                完成后用一句话总结结果。""");

        subConversationManager.addUserMessage(effectivePrompt);

        // Build tool filter — async definition-based
        boolean isFork = "_fork".equals(subAgentSpec.name());
        Predicate<String> toolFilter = ToolFilter.buildFilter(subAgentSpec, true, isFork);

        String workDir = worktreeResult != null ? worktreeResult.worktreePath() : workingDirectory;
        AgentLoop subAgent = createSubAgent(subConversationManager, subAgentSpec, toolFilter, modelOverride, name, workDir);

        return launchBackgroundTask(subAgent, subAgentSpec, description, name, worktreeResult);
    }

    // ── Sub-agent factory ─────────────────────────────────────────────────

    private AgentLoop createSubAgent(ConversationManager conv, SubAgentSpec spec,
                                      Predicate<String> toolFilter,
                                      String modelOverride, String name,
                                      String workDir) {
        // Create event queue
        BlockingQueue<com.mewcode.agent.AgentEvent> eventQueue = new LinkedBlockingQueue<>(256);

        int effMaxTurns = spec.effectiveMaxTurns(globalMaxTurns);
        String permMode = spec.effectivePermissionMode();

        // Sub-agents can't interactively respond to permission prompts —
        // "default" would cause every write/command to ASK → timeout → DENY.
        if ("default".equals(permMode)) {
            permMode = "dontAsk";
        }

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
        agent.setWorkingDirectory(workDir != null ? workDir : workingDirectory);
        agent.setToolFilter(toolFilter);

        // Inject TaskManager for tool usage tracking
        agent.setTaskManager(taskManager);

        // Configure as sub-agent
        String displayName = name != null ? name : spec.name();
        agent.configureAsSubAgent(displayName, spec.systemPromptOverride(),
                effMaxTurns, permMode);

        // Apply model override from the LLM call (takes precedence over spec.model)
        if (modelOverride != null && !modelOverride.isEmpty() && !"inherit".equals(modelOverride)) {
            agent.setModelOverride(modelOverride);
        } else if (spec.effectiveModel() != null && !"inherit".equals(spec.effectiveModel())) {
            agent.setModelOverride(spec.effectiveModel());
        }

        return agent;
    }

    // ── Background launch ───

    private ToolResult launchBackgroundTask(AgentLoop subAgent, SubAgentSpec spec, String description, String name,
                                            AgentWorktree.Result worktreeResult) {

        String taskName = name != null ? name : description;
        String taskId = taskManager.createTask(taskName);

        subAgent.setSubAgentTaskId(taskId);

        Thread thread = Thread.startVirtualThread(() -> {
            taskManager.setRunning(taskId, Thread.currentThread());
            try {
                String result = subAgent.runToCompletion();

                // Worktree cleanup after background completion
                if (worktreeResult != null) {
                    result = appendWorktreeInfo(result, worktreeResult);
                }

                taskManager.setCompleted(taskId, result);
            } catch (Exception e) {
                taskManager.setFailed(taskId, "Sub-agent error: " + e.getMessage());
                // Best-effort cleanup on failure
                if (worktreeResult != null) {
                    AgentWorktree.remove(worktreeResult.worktreePath(),
                            worktreeResult.worktreeBranch(), worktreeResult.gitRoot());
                }
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
                                       String description, String prompt, String name,
                                       AgentWorktree.Result wtResult) {
        String taskName = name != null ? name : ("sync-" + spec.name());
        String taskId = taskManager.createTask(taskName);

        subAgent.setSubAgentTaskId(taskId);

        Thread agentThread = Thread.startVirtualThread(() -> {
            taskManager.setRunning(taskId, Thread.currentThread());
            try {
                String result = subAgent.runToCompletion();
                // Worktree cleanup after sync completion
                if (wtResult != null) {
                    result = appendWorktreeInfo(result, wtResult);
                }
                taskManager.setCompleted(taskId, result);
            } catch (Exception e) {
                taskManager.setFailed(taskId, "Sub-agent error: " + e.getMessage());
                // Best-effort cleanup on failure
                if (wtResult != null) {
                    AgentWorktree.remove(wtResult.worktreePath(),
                            wtResult.worktreeBranch(), wtResult.gitRoot());
                }
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

    // ── Teammate spawn ──────────────────────────────────────────────────

    private ToolResult runAsTeammate(SubAgentSpec spec, String teamName, String description, String prompt,
                                     String modelOverride, String isolation) {
        var team = teamManager.getTeam(teamName);
        if (team == null) {
            return new ToolResult(false,
                    "Error: team '%s' not found. Create it first with TeamCreate.".formatted(teamName));
        }

        // Deduplicate member name
        String memberName = description.replaceAll("\\s+", "-").toLowerCase();
        if (memberName.length() > 30) memberName = memberName.substring(0, 30);
        int suffix = 2;
        String base = memberName;
        while (team.hasMember(memberName)) {
            memberName = base + "-" + suffix++;
        }

        // Build tool filter for the teammate
        var toolFilter = ToolFilter.buildFilter(spec, false, false);

        // Create a sub-registry with filtered tools + coordination tools
        com.mewcode.tool.ToolRegistry subRegistry = new com.mewcode.tool.ToolRegistry();
        for (com.mewcode.tool.Tool t : toolRegistry.getAllTools()) {
            if (toolFilter.test(t.getName())) {
                subRegistry.register(t);
            }
        }
        // Add coordination tools for teammates (NOT visible to regular sub-agents)
        subRegistry.register(new TeamTools.SendMessageTool(teamManager, memberName));

        // Gather peer names for addendum
        var otherMembers = team.memberNames();

        // Build addendum
        String addendum = TeammateRunner.buildTeammateAddendum(teamName, memberName, otherMembers);

        // Optional worktree isolation
        String workdir = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new SecureRandom().nextBytes(rndBytes);
                String slug = "agent-a" + HexFormat.of().formatHex(rndBytes).substring(0, 7);
                var wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                workdir = wtResult.worktreePath();
                String notice = AgentWorktree.buildNotice(
                        System.getProperty("user.dir"), wtResult.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (Exception e) {
                return new ToolResult(false, "Error creating teammate worktree: " + e.getMessage());
            }
        }

        // ── Run teammate's first turn synchronously ──
        // Create conversation and member
        ConversationManager conv = new ConversationManager();
        var eventQueue = new LinkedBlockingQueue<com.mewcode.agent.AgentEvent>(256);

        String effectiveWorkDir = workdir != null ? workdir : workingDirectory;
        int effMaxTurns = spec.effectiveMaxTurns(globalMaxTurns);

        AgentLoop turnAgent = new AgentLoop(
                provider, subRegistry, conv, eventQueue,
                effMaxTurns, streamTimeoutSeconds, permissionChecker);
        turnAgent.setWorkingDirectory(effectiveWorkDir);
        turnAgent.configureAsSubAgent(memberName, null, effMaxTurns, "dontAsk");

        var member = team.addMember(memberName, turnAgent, conv);
        member.provider = provider;
        member.toolRegistry = subRegistry;
        member.protocol = provider.getProviderName();
        member.maxTurns = effMaxTurns;
        member.streamTimeoutSeconds = streamTimeoutSeconds;
        member.permissionChecker = permissionChecker;
        member.workDir = effectiveWorkDir;
        member.active = true;

        // Inject addendum and pending messages
        if (!addendum.isEmpty()) {
            conv.addSystemReminder(addendum);
        }

        // Send initial task to the member's own mailbox so the inbox file
        // (alice.json) is created on disk. For TMUX teammates this is also
        // how the independent process picks up its first task.
        // Mark as read immediately since we inject the prompt directly below.
        team.sendMessage(TeammateRunner.LEAD_NAME, memberName, prompt);
        team.getMailBox().markAllRead(memberName);

        TeammateRunner.injectPendingMessages(team, memberName, conv);
        conv.addUserMessage(prompt);

        // Run first turn synchronously
        String result;
        try {
            result = turnAgent.runToCompletion();
        } catch (Exception e) {
            member.active = false;
            return new ToolResult(false,
                    "Teammate \"%s\" failed: %s".formatted(memberName, e.getMessage()));
        }

        // Send idle notification with the actual result
        String idleMsg = TeammateRunner.createIdleNotification(memberName, "completed initial task");
        team.sendMessage(memberName, TeammateRunner.LEAD_NAME,
                idleMsg + "\nResult: " + (result != null && !result.isEmpty() ? result : "(no output)"));

        // Transition to background idle polling
        member.thread = Thread.startVirtualThread(() -> {
            TeammateRunner.runIdleLoop(team, member);
        });

        String modeLabel = team.getMode().name();
        return new ToolResult(true,
                "Teammate \"%s\" spawned in team \"%s\" (mode: %s).\n\nOutput:\n%s"
                        .formatted(memberName, teamName, modeLabel,
                                result != null && !result.isEmpty() ? result : "(no output)"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Generate a random worktree slug: "agent-a" + 7 hex digits.
     */
    private static String generateWorktreeSlug() {
        byte[] rndBytes = new byte[4];
        new SecureRandom().nextBytes(rndBytes);
        return "agent-a" + HexFormat.of().formatHex(rndBytes).substring(0, 7);
    }

    /**
     * After sub-agent completion, check worktree for changes
     */
    private static String appendWorktreeInfo(String result, AgentWorktree.Result worktreeResult) {
        String safeResult = result != null ? result : "";
        if (WorktreeChanges.hasChanges(worktreeResult.worktreePath(), worktreeResult.headCommit())) {
            return safeResult + "\n\nWorktree kept at %s (branch %s) — has uncommitted changes or new commits."
                    .formatted(worktreeResult.worktreePath(), worktreeResult.worktreeBranch());
        } else {
            AgentWorktree.remove(worktreeResult.worktreePath(), worktreeResult.worktreeBranch(), worktreeResult.gitRoot());
            return safeResult;
        }
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

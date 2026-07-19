package com.mewcode;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentLoop;
import com.mewcode.command.CommandRegistry;
import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.hook.EventName;
import com.mewcode.hook.HookContext;
import com.mewcode.hook.HookEngine;
import com.mewcode.hook.HookErrorLogger;
import com.mewcode.hook.HookLoader;
import com.mewcode.instructions.InstructionsLoader;
import com.mewcode.mcp.McpManager;
import com.mewcode.memory.MemoryExtractor;
import com.mewcode.memory.MemoryManager;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.provider.LLMProvider;
import com.mewcode.provider.ProviderFactory;
import com.mewcode.session.SessionCleanup;
import com.mewcode.session.SessionManager;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillTool;
import com.mewcode.subagent.AgentCatalog;
import com.mewcode.subagent.AgentLoader;
import com.mewcode.subagent.AgentTool;
import com.mewcode.task.TaskList;
import com.mewcode.task.TaskManager;
import com.mewcode.task.TaskTools;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.impl.*;
import com.mewcode.teams.Coordinator;
import com.mewcode.teams.AgentNameRegistry;
import com.mewcode.teams.TeamManager;
import com.mewcode.teams.TeamTools;
import com.mewcode.teams.TeammateRunner;
import com.mewcode.tui.StreamingDisplay;
import com.mewcode.tui.TerminalUI;
import com.mewcode.worktree.StaleCleanup;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.worktree.WorktreeSessionStore;

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

public class MewCode {

    public static void main(String[] args) {
        // Force UTF-8 output on Windows (default is system code page like GBK)
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        try {
            // 1. Load configuration
            // Strategy: 
            //   - If command line arg provided, use that path
            //   - Otherwise, try ./mewcode.yaml first (user config)
            //   - If not found, load mewcode.yaml from classpath (built-in default)
            String configPath;
            AppConfig config;
            
            if (args.length > 0) {
                // User specified config path
                configPath = args[0];
                config = ConfigLoader.load(configPath);
            } else {
                File userConfig = new File("./mewcode.yaml");
                if (userConfig.exists()) {
                    // Use user's config file
                    configPath = "./mewcode.yaml";
                    config = ConfigLoader.load(configPath);
                } else {
                    // Use built-in default from classpath
                    configPath = "classpath:mewcode.yaml"; // for display purposes
                    config = ConfigLoader.loadFromClasspath();
                }
            }

            // 1b. Parse CLI flags (non-config args)
            boolean isTeammateMode = false;
            String cliTeamName = null;
            String cliAgentName = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--teammate" -> isTeammateMode = true;
                    case "--team-name" -> { if (i + 1 < args.length) cliTeamName = args[++i]; }
                    case "--agent-name" -> { if (i + 1 < args.length) cliAgentName = args[++i]; }
                }
            }

            // 1c. Resolve project root and working directory
            Path projectRoot;
            if (configPath.startsWith("classpath:")) {
                // When loading from classpath, use current directory as project root
                projectRoot = Paths.get(".").toAbsolutePath().normalize();
            } else {
                Path configFile = Paths.get(configPath).toAbsolutePath();
                projectRoot = configFile.getParent();
                if (projectRoot == null) {
                    projectRoot = Paths.get(".").toAbsolutePath();
                }
            }
            projectRoot = projectRoot.toRealPath();
            String workingDirectory = projectRoot.toString();

            // 1c. Load project instructions
            String instructionsContent = InstructionsLoader.load(workingDirectory);

            // 1d. Initialize memory manager
            MemoryManager memoryManager = new MemoryManager(workingDirectory);
            MemoryExtractor memoryExtractor = new MemoryExtractor();

            // 1e. Clean up expired sessions
            int maxSessionAgeDays = config.getMaxSessionAgeDays() > 0 ? config.getMaxSessionAgeDays() : 5;
            SessionCleanup.cleanIfNeeded(workingDirectory, maxSessionAgeDays);

            // 1f. Generate session ID for this run.
            // Mutable holder: /resume replaces the value so subsequent messages
            // append to the resumed session's JSONL file, not a new one.
            String[] sessionIdHolder = { SessionManager.newId() };

            // 1g. Load sub-agent definitions
            AgentCatalog agentCatalog = AgentLoader.loadAll(projectRoot);

            // 2. Build tool registry (built-in tools)
            ToolRegistry toolRegistry = buildToolRegistry(config);

            // 2a. Create task manager for sub-agent background tasks
            TaskManager taskManager = new TaskManager();

            // 2b. Create worktree manager (if enabled)
            AppConfig.WorktreeConfig worktreeConfig = config.getWorktree();
            WorktreeManager worktreeManager = null;
            ScheduledExecutorService cleanupExecutor = null;
            if (worktreeConfig.isEnabled()) {
                worktreeManager = new WorktreeManager(workingDirectory,
                        worktreeConfig.getSymlinkDirs(), worktreeConfig.getStaleCutoffHours());
            }

            // 2c. Create team manager
            TeamManager teamManager = new TeamManager();

            // 2d. Teammate mode: join existing team and enter polling loop
            if (isTeammateMode && cliTeamName != null && cliAgentName != null) {
                runTeammateMode(config, teamManager, cliTeamName, cliAgentName, workingDirectory);
                return;
            }

            // 3. Create provider
            LLMProvider provider = ProviderFactory.create(config, toolRegistry);

            // 4. Terminal UI (create early so MCP can use its writer for UTF-8 output)
            TerminalUI ui = new TerminalUI();
            ui.setWorkingDirectory(workingDirectory);
            ui.setMemoryManager(memoryManager);
            ui.setModel(config.getModel());
            // Wire resume callback: update sessionId so subsequent saves
            // append to the resumed session's JSONL file, not a new one.
            ui.setResumeCallback(resumedId -> {
                sessionIdHolder[0] = resumedId;
                ui.setCurrentSessionId(resumedId);
            });

            // 2b. Connect MCP servers and register their tools (AFTER UI is created)
            McpManager mcpManager = initMcp(config, toolRegistry, ui.getWriter());

            // 2c. Load skills (AFTER MCP is connected — so MCP tools are in ToolRegistry)
            SkillCatalog skillCatalog = SkillCatalog.loadCatalog(workingDirectory);

            // 2d. Initialize slash command registry
            CommandRegistry cmdRegistry = new CommandRegistry();

            // 5. Build permission checker
            PermissionMode mode = PermissionMode.fromString(config.getPermission().getMode());
            PermissionChecker permissionChecker = new PermissionChecker(mode, projectRoot);

            // Wire permission checker to TerminalUI for mode cycling
            ui.setPermissionChecker(permissionChecker);

            // Wire command registry (after permission checker so mode handler is wired)
            ui.setCommandRegistry(cmdRegistry);
            ui.setCurrentSessionId(sessionIdHolder[0]);

            // Wire skill list supplier for /status command
            ui.setSkillListSupplier(() -> skillCatalog.list().stream()
                    .map(SkillCatalog.SkillMeta::name).toList());

            // Print startup info
            ui.getWriter().println("已加载 provider: " + provider.getProviderName());
            ui.getWriter().println("model: " + config.getModel());
            ui.getWriter().println("tool: " + toolRegistry.size() + " 个");
            ui.getWriter().println("skill: " + skillCatalog.list().size() + " 个");
            ui.getWriter().println("SubAgent角色: " + agentCatalog.listNames().size() + " 个 (" + String.join(", ", agentCatalog.listNames()) + ")");
            ui.getWriter().println("迭代上限: " + config.getMaxIterations() + " 轮");
            ui.getWriter().println("权限模式: " + permissionChecker.getMode());
            if (worktreeManager != null) {
                ui.getWriter().println("Worktree: 已启用 (清理间隔: " + worktreeConfig.getStaleCleanupIntervalSeconds()
                        + "s, 过期阈值: " + worktreeConfig.getStaleCutoffHours() + "h)");
            }
            if (!instructionsContent.isEmpty()) {
                ui.getWriter().println("指令: 已加载 ("
                        + (instructionsContent.length() > 80
                            ? instructionsContent.substring(0, 80) + "..."
                            : instructionsContent)
                        + ")");
            }
            String memIndex = memoryManager.getMemoryIndexContent();
            if (!memIndex.isEmpty()) {
                int lineCount = (int) memIndex.lines().count();
                ui.getWriter().println("记忆: 已加载 (" + lineCount + " 条索引)");
            }
            ui.getWriter().flush();

            // 6. Conversation manager
            ConversationManager conversation = new ConversationManager(toolRegistry);
            ui.setConversation(conversation);

            // Inject long-term context (instructions + memory index + available skills)
            conversation.injectLongTermContext(instructionsContent,
                    memoryManager.getAllMemoryTitles());

            // 6a. Load hooks from .mewcode/hooks/
            Path hooksDir = Paths.get(workingDirectory, ".mewcode", "hooks");
            HookLoader.LoadedHooks loadedHooks = HookLoader.load(hooksDir);
            Path hooksErrorLog = Paths.get(workingDirectory, ".mewcode", "hooks-errors.log");
            HookErrorLogger hookErrorLogger = new HookErrorLogger(hooksErrorLog);
            HookEngine hookEngine = new HookEngine(hookErrorLogger);
            hookEngine.loadHooks(loadedHooks.valid());
            if (!loadedHooks.valid().isEmpty()) {
                ui.getWriter().println("Hook: 已加载 " + loadedHooks.valid().size() + " 条规则");
            }
            for (String warning : loadedHooks.warnings()) {
                ui.getWriter().println(" ⚠ Hook 警告: " + warning);
            }
            ui.getWriter().flush();

            // 6b. Register SkillTool in toolRegistry

            // 6c. Wire skill commands into CommandRegistry (pending — needs agent holder)

            // 7. Streaming display
            StreamingDisplay display = new StreamingDisplay(ui.getWriter());

            // 8. Event queue: bounded to 1000 for back-pressure
            BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>(1000);

            // 9. Agent loop — created per user input
            AgentLoop[] currentAgent = { null };

            // Register SkillTool now that currentAgent holder exists
            SkillTool skillTool = new SkillTool(
                    skillCatalog,
                    () -> currentAgent[0],
                    () -> currentAgent[0]
            );
            toolRegistry.register(skillTool);

            // Register Agent tool (sub-agent system)
            boolean backgroundEnabled = config.getSubAgent().getBackground().isEnabled();
            int subAgentMaxTurns = config.getSubAgent().getMaxTurns();
            AgentTool agentTool = new AgentTool(
                    provider, toolRegistry, agentCatalog, taskManager,
                    permissionChecker, subAgentMaxTurns,
                    config.getStreamTimeoutSeconds(), backgroundEnabled,
                    workingDirectory);
            toolRegistry.register(agentTool);

            // Wire worktree manager to AgentTool (for isolation: "worktree")
            if (worktreeManager != null) {
                agentTool.setWorktreeManager(worktreeManager);
                // Register worktree session tools
                toolRegistry.register(new EnterWorktreeTool(worktreeManager, sessionIdHolder[0]));
                toolRegistry.register(new ExitWorktreeTool(worktreeManager));
                // Load and restore any saved worktree session
                var savedSession = WorktreeSessionStore.load(workingDirectory);
                if (savedSession != null) {
                    WorktreeSessionStore.restoreSession(savedSession);
                }
                // Start stale cleanup loop
                cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "worktree-cleanup");
                    t.setDaemon(true);
                    return t;
                });
                StaleCleanup.startCleanupLoop(cleanupExecutor, workingDirectory,
                        worktreeConfig.getStaleCleanupIntervalSeconds(),
                        worktreeConfig.getStaleCutoffHours());
            }

            // Wire team manager to AgentTool (for team_name support)
            agentTool.setTeamManager(teamManager);

            // Register team coordination tools
            toolRegistry.register(new TeamTools.TeamCreateTool(teamManager));
            toolRegistry.register(new TeamTools.TeamDeleteTool(teamManager));
            // SendMessage for the lead — allows communication with teammates
            toolRegistry.register(new TeamTools.SendMessageTool(teamManager, "lead"));

            // Register shared task tools (deferred, for Lead and teammates)
            TaskList sharedTaskList = new TaskList("shared", workingDirectory);
            toolRegistry.register(new TaskTools.TaskCreateTool(sharedTaskList));
            toolRegistry.register(new TaskTools.TaskGetTool(sharedTaskList));
            toolRegistry.register(new TaskTools.TaskListTool(sharedTaskList));
            toolRegistry.register(new TaskTools.TaskUpdateTool(sharedTaskList));

            // Register background sub-agent task management tools (TaskStop has no shared equivalent)
            toolRegistry.register(new TaskStopTool(taskManager));

            // Wire skill commands into CommandRegistry
            cmdRegistry.setSkillRegistry(skillCatalog, () -> currentAgent[0], () -> currentAgent[0]);
            cmdRegistry.registerSkillCommands();

            // 10. Start event consumer thread
            Thread eventConsumer = startEventConsumer(eventQueue, display, ui, permissionChecker);

            // 11. Start interactive input loop
            ui.start(input -> {
                // If an agent is waiting for permission, dispatch input there
                if (currentAgent[0] != null && currentAgent[0].isWaitingForPermission()) {
                    currentAgent[0].respondToPermission(input.trim());
                    return;
                }

                // Cancel any in-flight agent
                if (currentAgent[0] != null) {
                    currentAgent[0].cancel();
                }

                // Add user message to conversation
                conversation.addUserMessage(input);

                // Save user message to session JSONL
                SessionManager.saveMessage(workingDirectory, sessionIdHolder[0], "user", input);

                // Create agent loop
                AgentLoop agent = new AgentLoop(
                        provider,
                        toolRegistry,
                        conversation,
                        eventQueue,
                        config.getMaxIterations(),
                        config.getStreamTimeoutSeconds(),
                        permissionChecker
                );
                agent.setPlanMode(ui.isPlanMode());
                agent.setWorkingDirectory(workingDirectory);
                agent.setSessionId(sessionIdHolder[0]);
                // Wire memory extraction
                agent.setMemoryExtractor(memoryExtractor);
                agent.setMemoryManager(memoryManager);
                agent.setProviderForMemory(provider);
                agent.setHookEngine(hookEngine);

                // Wire AgentTool: update parent conversation each turn (for Fork support)
                agentTool.setParentConversation(conversation);
                agentTool.setParentReplacementState(agent.getReplacementState());

                // Wire TaskManager: drain completed notifications before each turn
                agent.setNotificationFn(() -> {
                    var notes = new java.util.ArrayList<String>();
                    for (var n : taskManager.drainNotifications()) {
                        notes.add(n.format());
                    }
                    // Also drain team mailbox notifications
                    notes.addAll(TeammateRunner.drainLeadMailbox(teamManager));
                    return notes;
                });

                // Coordinator mode: two-lock gating
                boolean coordinatorConfigEnabled = config.getTeam().getCoordinator().isEnabled();
                boolean coordinatorEnvEnabled = "1".equals(System.getenv("MEWCODE_COORDINATOR"));
                if (coordinatorConfigEnabled && coordinatorEnvEnabled) {
                    agent.setToolFilter(Coordinator::isCoordinatorTool);
                }

                currentAgent[0] = agent;

                // Wire agent loop to TerminalUI for /compact command
                ui.setAgentLoop(agent);

                // Run agent on its own thread
                Thread agentThread = new Thread(agent, "agent-loop");
                agentThread.start();
            }, () -> {
                conversation.clear();
                // Clear active skills when conversation is cleared
                if (currentAgent[0] != null) {
                    currentAgent[0].clearActiveSkills();
                }
            });

            // Cleanup
            if (currentAgent[0] != null) {
                currentAgent[0].cancel();
            }
            eventConsumer.interrupt();
            memoryExtractor.shutdown();
            if (cleanupExecutor != null) {
                cleanupExecutor.shutdownNow();
            }

            // ---- hook: shutdown ----
            hookEngine.runHooks(EventName.SHUTDOWN, HookContext.of(EventName.SHUTDOWN));

            // Shutdown MCP connections
            shutdownMcp(mcpManager);

        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Start a daemon thread that consumes AgentEvents from the queue and renders
     * them via StreamingDisplay.
     */
    private static Thread startEventConsumer(BlockingQueue<AgentEvent> queue, StreamingDisplay display,
                                              TerminalUI ui, PermissionChecker permissionChecker) {
        Thread consumer = new Thread(() -> {
            PrintWriter writer = ui.getWriter();
            while (true) {
                try {
                    AgentEvent event = queue.take();

                    switch (event.getType()) {
                        case TEXT_DELTA -> {
                            if (event.getText() != null && event.getChunkType() != null) {
                                display.onChunk(event.getText(), event.getChunkType());
                            }
                        }
                        case TOOL_CALL_START -> {
                            writer.println("\n ⚙ 正在执行工具 " + event.getToolName() + "...");
                            writer.flush();
                        }
                        case TOOL_CALL_RESULT -> {
                            // Tool results are fed back to the model; no UI display needed
                        }
                        case TOKEN_USAGE -> {
                            long create = event.getCacheCreationTokens();
                            long read = event.getCacheReadTokens();
                            if (create > 0 || read > 0) {
                                writer.print("[cache: " + read + " tokens read, " + create + " created]");
                                writer.flush();
                            }
                            // Update token counts for /status display
                            ui.setTokenCounts(event.getInputTokens(), event.getOutputTokens());
                        }
                        case PROGRESS -> {
                            // Progress messages are for internal tracking; skip UI noise
                        }
                        case COMPACT -> {
                            if (event.getMessage() != null && !event.getMessage().isEmpty()) {
                                writer.println(" ⏤ " + event.getMessage());
                                writer.flush();
                            }
                        }
                        case LOOP_STARTED -> {
                            // Agent loop begins — nothing to display
                        }
                        case LOOP_FINISHED -> {
                            display.onComplete();
                            writer.println("✓ 完成");
                            writer.flush();
                        }
                        case ERROR -> {
                            display.onComplete();
                            writer.println("✗ " + (event.getMessage() != null ? event.getMessage() : "未知错误"));
                            writer.flush();
                        }
                        case CANCELLED -> {
                            display.onComplete();
                            writer.println("⊘ " + (event.getMessage() != null ? event.getMessage() : "已取消"));
                            writer.flush();
                        }
                        case UNKNOWN_TOOL -> {
                            display.onComplete();
                            writer.println("✗ " + (event.getMessage() != null ? event.getMessage() : "未知工具"));
                            writer.flush();
                        }
                        case PERMISSION_REQUIRED -> {
                            // Render the permission confirmation prompt
                            ToolCall tc = event.getPermissionToolCall();
                            String toolName = event.getToolName() != null ? event.getToolName() : "?";
                            String description = event.getMessage() != null ? event.getMessage() : "需要用户确认";

                            writer.println();
                            writer.println("════════════════════权限确认 ════════════════════");
                            writer.println(" 工具: " + toolName);
                            writer.println(" 操作: " + description);
                            writer.println("════════════════════════════════════════════════");
                            writer.println(" [Y] 本次放行  [A] 总是放行(本会话)  [N] 拒绝");
                            writer.println("════════════════════════════════════════════════");
                            writer.flush();
                        }
                        case PERMISSION_RESPONSE -> {
                            // Handled inside AgentLoop via permissionResponseQueue;
                            // no rendering needed here.
                        }
                        case MEMORY_TICK -> {
                            // Memory extraction may be triggered asynchronously;
                            // no rendering needed here.
                        }
                    }
                } catch (InterruptedException e) {
                    break; // consumer thread shutting down
                } catch (Exception e) {
                    writer.println("[事件消费异常] " + e.getMessage());
                    writer.flush();
                }
            }
        }, "event-consumer");
        consumer.setDaemon(true);
        consumer.start();
        return consumer;
    }

    /**
     * Teammate mode entry point: join an existing team and enter the mailbox polling loop.
     * Used by the TMUX backend to run teammates in separate terminal windows.
     */
    private static void runTeammateMode(AppConfig config, TeamManager teamManager,
                                        String teamName, String agentName, String workingDirectory) {
        System.out.println("[Teammate] Joining team '" + teamName + "' as '" + agentName + "'...");

        // Register this teammate in the team's name registry
        AgentNameRegistry.getInstance().register(agentName, agentName);

        // Build tool registry
        ToolRegistry toolRegistry = buildToolRegistry(config);

        // Create provider
        LLMProvider provider = ProviderFactory.create(config, toolRegistry);

        // Create conversation
        ConversationManager conv = new ConversationManager(toolRegistry);

        // Build agent loop
        var eventQueue = new LinkedBlockingQueue<AgentEvent>(256);
        PermissionChecker permissionChecker = new PermissionChecker(
                PermissionMode.BYPASS,
                Path.of(workingDirectory)
        );
        AgentLoop agent = new AgentLoop(
                provider, toolRegistry, conv, eventQueue,
                config.getMaxIterations(), config.getStreamTimeoutSeconds(),
                permissionChecker
        );
        agent.setWorkingDirectory(workingDirectory);
        agent.configureAsSubAgent(agentName, null, config.getMaxIterations(), "dontAsk");

        // Create or get team (team must already exist from the lead process)
        // For TMUX mode, we create a team placeholder since the lead's process
        // already has the real Team object in memory
        TeamManager.TeamMode mode = TeamManager.TeamMode.TMUX;
        var team = teamManager.createTeam(teamName, mode);

        // Register this member
        var member = team.addMember(agentName, agent, conv);
        member.active = true;
        member.thread = Thread.currentThread();

        // Register SendMessage tool so the teammate can communicate
        toolRegistry.register(new TeamTools.SendMessageTool(teamManager, agentName));

        // Enter the teammate polling loop — blocks until shutdown or interrupt
        // First inject any pending messages, then poll for initial task
        TeammateRunner.injectPendingMessages(team, agentName, conv);

        // Wait for the first task from the mailbox
        var messages = team.getMailBox().readUnread(agentName);
        if (!messages.isEmpty()) {
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(agentName);
            conv.addUserMessage(sb.toString());
        } else {
            // No initial task — just wait
            conv.addUserMessage("You are teammate '" + agentName + "' in team '" + teamName
                    + "'. Wait for tasks from your lead. Use SendMessage to communicate.");
        }

        // Run first turn
        try {
            agent.runToCompletion();
        } catch (Exception e) {
            System.err.println("[Teammate] Error on first turn: " + e.getMessage());
        }

        // Send idle notification
        team.sendMessage(agentName, TeammateRunner.LEAD_NAME,
                TeammateRunner.createIdleNotification(agentName, "completed initial task"));

        // Enter idle polling loop (same as in-process teammate)
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(TeammateRunner.IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            messages = team.getMailBox().readUnread(agentName);
            if (messages.isEmpty()) continue;

            boolean shutdown = false;
            for (var msg : messages) {
                if (TeammateRunner.isShutdownRequest(msg.text())) {
                    shutdown = true;
                    break;
                }
            }
            if (shutdown) {
                team.getMailBox().markAllRead(agentName);
                break;
            }

            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(agentName);
            conv.addUserMessage(sb.toString());

            try {
                agent.runToCompletion();
            } catch (Exception e) {
                System.err.println("[Teammate] Error: " + e.getMessage());
            }

            team.sendMessage(agentName, TeammateRunner.LEAD_NAME,
                    TeammateRunner.createIdleNotification(agentName, "completed follow-up"));
        }

        member.active = false;
        System.out.println("[Teammate] Shutting down.");
    }

    private static ToolRegistry buildToolRegistry(AppConfig config) {
        String workingDirectory = config.getTool().getWorkingDirectory();
        int timeout = config.getTool().getTimeoutSeconds();

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolSearchTool(registry));
        registry.registerAll(
                new ReadFileTool(workingDirectory),
                new WriteFileTool(workingDirectory),
                new EditFileTool(workingDirectory),
                new GlobTool(workingDirectory),
                new GrepTool(workingDirectory),
                new ExecuteCommandTool(workingDirectory, timeout)
        );
        return registry;
    }

    /**
     * Initialize MCP connections if configured.
     *
     * @param config   the application configuration
     * @param registry the tool registry to register into
     * @param writer   the PrintWriter for output (from TerminalUI)
     * @return the McpManager (non-null even if no servers configured — it's a no-op manager)
     */
    private static McpManager initMcp(AppConfig config, ToolRegistry registry, java.io.PrintWriter writer) {
        AppConfig.McpConfigNode mcpConfig = config.getMcp();
        if (mcpConfig == null || mcpConfig.getServers() == null || mcpConfig.getServers().isEmpty()) {
            return null;
        }
        McpManager manager = new McpManager(mcpConfig.getServers());
        manager.registerAllMcpTools(registry, writer);
        return manager;
    }

    /**
     * Gracefully shut down all MCP client connections.
     */
    private static void shutdownMcp(McpManager manager) {
        if (manager != null) {
            manager.shutdown();
        }
    }
}

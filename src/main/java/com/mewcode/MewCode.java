package com.mewcode;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentLoop;
import com.mewcode.command.CommandRegistry;
import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.conversation.ConversationManager;
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
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.*;
import com.mewcode.tui.StreamingDisplay;
import com.mewcode.tui.TerminalUI;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MewCode {

    public static void main(String[] args) {
        // Force UTF-8 output on Windows (default is system code page like GBK)
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        try {
            // 1. Load configuration
            String configPath = args.length > 0 ? args[0] : "./mewcode.yaml";
            AppConfig config = ConfigLoader.load(configPath);

            // 1b. Resolve project root and working directory
            java.nio.file.Path configFile = Paths.get(configPath).toAbsolutePath();
            java.nio.file.Path projectRoot = configFile.getParent();
            if (projectRoot == null) {
                projectRoot = Paths.get(".").toAbsolutePath();
            }
            projectRoot = projectRoot.toRealPath();
            String workingDirectory = projectRoot.toString();

            // 1c. Load project instructions
            String instructionsContent = InstructionsLoader.load(workingDirectory);

            // 1d. Initialize memory manager
            MemoryManager memoryManager = new MemoryManager(workingDirectory);
            MemoryExtractor memoryExtractor = new MemoryExtractor();

            // 1e. Clean up expired sessions
            int maxSessionAgeDays = config.getMaxSessionAgeDays() > 0 ? config.getMaxSessionAgeDays() : 30;
            SessionCleanup.cleanIfNeeded(workingDirectory, maxSessionAgeDays);

            // 1f. Generate session ID for this run.
            // Mutable holder: /resume replaces the value so subsequent messages
            // append to the resumed session's JSONL file, not a new one.
            String[] sessionIdHolder = { SessionManager.newId() };

            // 2. Build tool registry (built-in tools)
            ToolRegistry toolRegistry = buildToolRegistry(config);

            // 2b. Connect MCP servers and register their tools
            McpManager mcpManager = initMcp(config, toolRegistry);

            // 2c. Initialize slash command registry
            CommandRegistry cmdRegistry = new CommandRegistry();

            // 3. Create provider
            LLMProvider provider = ProviderFactory.create(config, toolRegistry);

            // 4. Terminal UI
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

            // 5. Build permission checker
            PermissionMode mode = PermissionMode.fromString(config.getPermission().getMode());
            PermissionChecker permissionChecker = new PermissionChecker(mode, projectRoot);

            // Wire permission checker to TerminalUI for mode cycling
            ui.setPermissionChecker(permissionChecker);

            // Wire command registry (after permission checker so mode handler is wired)
            ui.setCommandRegistry(cmdRegistry);
            ui.setCurrentSessionId(sessionIdHolder[0]);

            // Print startup info
            ui.getWriter().println("已加载 provider: " + provider.getProviderName());
            ui.getWriter().println("模型: " + config.getModel());
            ui.getWriter().println("工具: " + toolRegistry.size() + " 个");
            ui.getWriter().println("迭代上限: " + config.getMaxIterations() + " 轮");
            ui.getWriter().println("权限模式: " + permissionChecker.getMode());
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

            // Inject long-term context (instructions + memory index)
            conversation.injectLongTermContext(instructionsContent,
                    memoryManager.getAllMemoryTitles());

            // 7. Streaming display
            StreamingDisplay display = new StreamingDisplay(ui.getWriter());

            // 8. Event queue: bounded to 1000 for back-pressure
            BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>(1000);

            // 9. Agent loop — created per user input
            AgentLoop[] currentAgent = { null };

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
                currentAgent[0] = agent;

                // Wire agent loop to TerminalUI for /compact command
                ui.setAgentLoop(agent);

                // Run agent on its own thread
                Thread agentThread = new Thread(agent, "agent-loop");
                agentThread.start();
            }, conversation::clear);

            // Cleanup
            if (currentAgent[0] != null) {
                currentAgent[0].cancel();
            }
            eventConsumer.interrupt();
            memoryExtractor.shutdown();

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

    private static ToolRegistry buildToolRegistry(AppConfig config) {
        String wd = config.getTool().getWorkingDirectory();
        int timeout = config.getTool().getTimeoutSeconds();

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolSearchTool(registry));
        registry.registerAll(
                new ReadFileTool(wd),
                new WriteFileTool(wd),
                new EditFileTool(wd),
                new GlobTool(wd),
                new GrepTool(wd),
                new ExecuteCommandTool(wd, timeout)
        );
        return registry;
    }

    /**
     * Initialize MCP connections if configured.
     *
     * @return the McpManager (non-null even if no servers configured — it's a no-op manager)
     */
    private static McpManager initMcp(AppConfig config, ToolRegistry registry) {
        AppConfig.McpConfigNode mcpConfig = config.getMcp();
        if (mcpConfig == null || mcpConfig.getServers() == null || mcpConfig.getServers().isEmpty()) {
            return null;
        }
        McpManager manager = new McpManager(mcpConfig.getServers());
        manager.registerAllMcpTools(registry);
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

package com.mewcode.tui;

import com.mewcode.agent.AgentLoop;
import com.mewcode.command.Command;
import com.mewcode.command.CommandContext;
import com.mewcode.command.CommandRegistry;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.memory.MemoryEntry;
import com.mewcode.memory.MemoryManager;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.session.SessionManager;
import com.mewcode.session.SessionRecovery;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * JLine-based interactive terminal UI loop.
 *
 * <p>Slash commands are dispatched through {@link CommandRegistry} instead of
 * a hardcoded switch. Tab completion, alias support, and conflict detection
 * are provided by the registry.
 */
public class TerminalUI {

    @FunctionalInterface
    public interface InputHandler {
        /** Called with each non-command user input line. */
        void handle(String input);
    }

    private static final String PROMPT = "\033[1;32m> \033[0m"; // bold green

    private LineReader lineReader;
    private PrintWriter writer;
    private volatile boolean planMode;
    private PermissionChecker permissionChecker;
    private ConversationManager conversation;
    private MemoryManager memoryManager;
    private String workingDirectory;
    private Consumer<String> resumeCallback;
    private CommandRegistry cmdRegistry;
    private int totalInputTokens;
    private int totalOutputTokens;
    private String currentSessionId;
    private java.util.function.Supplier<List<String>> skillListSupplier;

    public TerminalUI() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            writer = terminal.writer();
            lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new SlashCommandCompleter())
                    .build();
        } catch (Exception e) {
            System.err.println("无法初始化终端: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Returns the terminal's writer for use by other components. */
    public PrintWriter getWriter() {
        return writer;
    }

    /** @return true if Plan Mode is active (only read-only tools are available). */
    public boolean isPlanMode() {
        return planMode;
    }

    /** Wire the permission checker for mode display and cycling. */
    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * Wire the agent loop for /compact callback.
     * Also updates the registry's compact handler so /compact always
     * delegates to the current agent.
     */
    public void setAgentLoop(AgentLoop agentLoop) {
        if (cmdRegistry != null && agentLoop != null) {
            final AgentLoop captured = agentLoop;
            cmdRegistry.setCompactHandler(() -> {
                try {
                    return captured.forceCompact();
                } catch (Exception e) {
                    return "压缩失败: " + e.getMessage();
                }
            });
        }
    }

    /** Wire the conversation manager for session resume. */
    public void setConversation(ConversationManager conversation) {
        this.conversation = conversation;
    }

    /** Wire the memory manager for /memory commands. */
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /** Set the working directory for session listing. */
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /** Set a callback to handle session resume (rebuilds agent state). */
    public void setResumeCallback(Consumer<String> callback) {
        this.resumeCallback = callback;
    }

    /** Wire the command registry for slash command dispatch and Tab completion. */
    public void setCommandRegistry(CommandRegistry cmdRegistry) {
        this.cmdRegistry = cmdRegistry;
        // Wire permission mode switching callback
        if (permissionChecker != null) {
            cmdRegistry.setPermissionModeHandler(modeName -> {
                PermissionMode newMode = PermissionMode.fromString(modeName);
                permissionChecker.setMode(newMode);
                // Sync planMode if mode is explicitly set to PLAN
                if (newMode == PermissionMode.PLAN) {
                    planMode = true;
                    permissionChecker.setPlanMode(true);
                }
            });
        }
    }

    /** Update token counts for /status display. */
    public void setTokenCounts(int inputTokens, int outputTokens) {
        this.totalInputTokens = inputTokens;
        this.totalOutputTokens = outputTokens;
    }

    /** Update the current session ID for /session display. */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    /** Set supplier for skill name list (used by /status and other commands). */
    public void setSkillListSupplier(java.util.function.Supplier<List<String>> supplier) {
        this.skillListSupplier = supplier;
    }

    /**
     * Start the read-eval-print loop.
     *
     * @param handler called with each non-command user input
     *                (on the calling thread)
     * @param onClear called when /clear is entered
     */
    public void start(InputHandler handler, Runnable onClear) {
        // Graceful cleanup on Ctrl+C / shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            writer.println("\nGoodbye!");
            writer.flush();
        }));

        writer.println("MewCode - 终端 AI 助手");
        writer.println("输入 /help 查看命令, /plan 调研模式, /do 执行模式, "
                + "/mode 切换权限, /compact 压缩上下文, /clear 清空对话, "
                + "/skill 技能列表, /resume 恢复会话, /memory 查看记忆, /exit 退出");
        writer.println();
        writer.flush();

        while (true) {
            try {
                // Dynamic prompt with permission-mode indicator
                String dynamicPrompt = PROMPT;
                if (permissionChecker != null) {
                    PermissionMode mode = permissionChecker.getMode();
                    String indicator = switch (mode) {
                        case DEFAULT       -> "\033[1;32m[DEFAULT]\033[0m ";
                        case ACCEPT_EDITS  -> "\033[1;32m[ACCEPT_EDITS]\033[0m ";
                        case PLAN          -> "\033[1;33m[PLAN]\033[0m ";
                        case BYPASS        -> "\033[1;31m[YOLO]\033[0m ";
                    };
                    dynamicPrompt = indicator + PROMPT;
                }

                String input = lineReader.readLine(dynamicPrompt);

                if (input == null) {
                    // EOF (Ctrl+D)
                    writer.println("Goodbye!");
                    writer.flush();
                    System.exit(0);
                    return;
                }

                input = input.trim();

                if (input.isEmpty()) {
                    continue;
                }

                // ── Command dispatch (via CommandRegistry) ────────────
                if (input.startsWith("/")) {
                    dispatchCommand(input, handler, onClear);
                } else {
                    handler.handle(input);
                }
            } catch (UserInterruptException e) {
                // Ctrl+C at prompt
                writer.println("\nGoodbye!");
                writer.flush();
                System.exit(0);
                return;
            } catch (EndOfFileException e) {
                // Ctrl+D
                writer.println("Goodbye!");
                writer.flush();
                System.exit(0);
                return;
            } catch (Exception e) {
                writer.println("输入错误: " + e.getMessage());
                writer.flush();
            }
        }
    }

    // ── Command dispatch ──────────────────────────────────────────────────

    private void dispatchCommand(String input, InputHandler handler,
                                  Runnable onClear) {
        // Parse: first space separates command name from args
        String body = input.substring(1); // strip leading /
        if (body.isEmpty()) {
            return; // bare "/" — no-op
        }

        String[] parts = body.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase(Locale.ROOT);
        String cmdArgs = parts.length > 1 ? parts[1] : "";

        if (cmdRegistry == null) {
            writer.println("命令系统未初始化");
            writer.flush();
            return;
        }

        var optCmd = cmdRegistry.find(cmdName);
        if (optCmd.isEmpty()) {
            writer.println("未知命令: /" + cmdName
                    + " — 输入 /help 查看可用命令");
            writer.flush();
            return;
        }

        Command command = optCmd.get();

        switch (command.type()) {
            case LOCAL -> {
                CommandContext ctx = buildCommandContext(cmdArgs);
                String output = cmdRegistry.execute(cmdName, ctx);
                if (output != null && !output.isEmpty()) {
                    writer.println(output);
                }
                writer.flush();
            }
            case LOCAL_UI -> handleLocalUiCommand(command.name(), onClear);
            case PROMPT -> {
                CommandContext ctx = buildCommandContext(cmdArgs);
                String prompt = cmdRegistry.execute(cmdName, ctx);
                if (prompt != null && !prompt.isBlank()) {
                    handler.handle(prompt);
                }
            }
        }
    }

    // ── LOCAL_UI handlers ─────────────────────────────────────────────────

    private void handleLocalUiCommand(String name, Runnable onClear) {
        switch (name) {
            case "clear" -> {
                onClear.run();
                planMode = false;
                if (permissionChecker != null) {
                    permissionChecker.setPlanMode(false);
                }
                writer.println("对话已清空");
                writer.flush();
            }
            case "plan" -> {
                planMode = true;
                if (permissionChecker != null) {
                    permissionChecker.setPlanMode(true);
                    permissionChecker.setMode(PermissionMode.PLAN);
                }
                writer.println("已进入 Plan Mode，仅可调研（只读工具）");
                writer.flush();
            }
            case "do" -> {
                planMode = false;
                if (permissionChecker != null) {
                    permissionChecker.setPlanMode(false);
                    permissionChecker.setMode(PermissionMode.DEFAULT);
                }
                writer.println("已退出 Plan Mode，恢复 DEFAULT 权限模式");
                writer.flush();
            }
            case "mode" -> {
                if (permissionChecker != null) {
                    PermissionMode current = permissionChecker.getMode();
                    // Skip PLAN in normal cycling
                    PermissionMode next = switch (current) {
                        case DEFAULT       -> PermissionMode.ACCEPT_EDITS;
                        case ACCEPT_EDITS  -> PermissionMode.BYPASS;
                        case BYPASS        -> PermissionMode.DEFAULT;
                        case PLAN          -> PermissionMode.DEFAULT;
                    };
                    permissionChecker.setMode(next);
                    // /mode exits plan mode if currently in plan
                    if (current == PermissionMode.PLAN) {
                        planMode = false;
                        permissionChecker.setPlanMode(false);
                    }
                    String desc = switch (next) {
                        case DEFAULT -> "默认 — 读取自动放行，写入/命令需确认";
                        case ACCEPT_EDITS -> "接受编辑 — 读取/写入自动放行，命令需确认";
                        case BYPASS -> "YOLO — 全部自动放行（危险）";
                        case PLAN -> "Plan — 只读调研模式";
                    };
                    writer.println("权限模式 → " + desc);
                } else {
                    writer.println("权限检查器未初始化");
                }
                writer.flush();
            }
            case "exit" -> {
                writer.println("Goodbye!");
                writer.flush();
                System.exit(0);
            }
            case "resume" -> {
                handleResume();
            }
        }
    }

    // ── CommandContext builder ─────────────────────────────────────────────

    private CommandContext buildCommandContext(String args) {
        String model = modelForContext;
        return new CommandContext(
                args,
                workingDirectory != null ? workingDirectory : ".",
                model,
                () -> permissionChecker != null
                        ? permissionChecker.getMode().name().toLowerCase(Locale.ROOT)
                        : "default",
                () -> planMode,
                () -> 0, // tool count updated externally
                () -> new int[]{totalInputTokens, totalOutputTokens},
                () -> {
                    if (memoryManager != null) {
                        List<MemoryEntry> entries = memoryManager.listAll();
                        return entries.stream()
                                .map(e -> "[" + e.type() + "] " + e.description())
                                .toList();
                    }
                    return List.of();
                },
                () -> {
                    if (memoryManager != null) {
                        for (var entry : memoryManager.listAll()) {
                            memoryManager.deleteEntry(entry.name(), entry.type());
                        }
                    }
                },
                () -> {
                    if (currentSessionId != null) {
                        return "当前会话: " + currentSessionId;
                    }
                    if (workingDirectory != null) {
                        var sessions = SessionManager
                                .listSessions(workingDirectory);
                        if (sessions.isEmpty()) {
                            return "没有找到之前的会话记录";
                        }
                        var sb = new StringBuilder("会话列表 ("
                                + sessions.size() + " 个):\n");
                        int maxShow = Math.min(sessions.size(), 20);
                        for (int i = 0; i < maxShow; i++) {
                            var s = sessions.get(i);
                            String title = s.firstMessage().isEmpty()
                                    ? "(无标题)" : s.firstMessage();
                            String time = SessionManager
                                    .formatRelativeTime(s.modTime());
                            sb.append("  [").append(i + 1).append("] ")
                                    .append(s.id())
                                    .append(" — ").append(s.messageCount())
                                    .append(" 条 | ").append(time)
                                    .append("\n");
                            sb.append("      ").append(title).append("\n");
                        }
                        return sb.toString();
                    }
                    return "工作目录未设置";
                },
                () -> skillListSupplier != null
                        ? skillListSupplier.get()
                        : List.of()
        );
    }

    /** Called by MewCode to update the model name for /status display. */
    public void setModel(String model) {
        this.modelForContext = model;
    }
    private String modelForContext = "unknown";

    // ── /resume command ───────────────────────────────────────────────────

    private void handleResume() {
        if (workingDirectory == null) {
            writer.println("工作目录未设置");
            writer.flush();
            return;
        }

        List<SessionManager.SessionInfo> sessions = SessionManager.listSessions(workingDirectory);
        if (sessions.isEmpty()) {
            writer.println("没有找到之前的会话记录");
            writer.flush();
            return;
        }

        writer.println();
        writer.println("═══════════ 会话列表 ═══════════");
        int maxShow = Math.min(sessions.size(), 20);
        for (int i = 0; i < maxShow; i++) {
            SessionManager.SessionInfo s = sessions.get(i);
            String title = s.firstMessage().isEmpty() ? "(无标题)" : s.firstMessage();
            String time = SessionManager.formatRelativeTime(s.modTime());
            String size = SessionManager.formatFileSize(s.fileSize());
            writer.printf(" [%d] %s — %s 条消息 | %s | %s%n",
                    i + 1, s.id(), s.messageCount(), size, time);
            writer.println("     " + title);
        }
        writer.println("═════════════════════════════════");
        writer.println("输入编号恢复会话 (如 1)，或按 Enter 取消:");
        writer.flush();

        try {
            String input = lineReader.readLine("恢复> ");
            if (input == null || input.trim().isEmpty()) return;

            int idx;
            try {
                idx = Integer.parseInt(input.trim()) - 1;
            } catch (NumberFormatException e) {
                writer.println("无效编号");
                writer.flush();
                return;
            }

            if (idx < 0 || idx >= maxShow) {
                writer.println("编号超出范围");
                writer.flush();
                return;
            }

            SessionManager.SessionInfo selected = sessions.get(idx);

            // Load raw messages
            List<SessionManager.SessionMessage> rawMessages =
                    SessionManager.loadSession(workingDirectory, selected.id());

            // Run recovery
            SessionRecovery.RecoveryResult recovery = SessionRecovery.recover(
                    rawMessages, 200_000);

            // Log warnings
            for (String warning : recovery.warnings()) {
                writer.println(" ⚠ " + warning);
            }

            // Clear current conversation and reload
            if (conversation != null) {
                conversation.clear();

                // Accumulate consecutive tool_results into a single message
                List<ToolResultBlock> pendingToolResults = new ArrayList<>();

                for (SessionManager.SessionMessage msg : recovery.messages()) {
                    String role = msg.role();
                    if ("tool_result".equals(role)) {
                        pendingToolResults.add(new ToolResultBlock(
                                msg.toolUseId() != null ? msg.toolUseId() : "",
                                msg.content(),
                                msg.isError() != null && msg.isError()));
                    } else {
                        // Flush pending tool_results before non-tool_result message
                        if (!pendingToolResults.isEmpty()) {
                            conversation.addToolResultsMessage(new ArrayList<>(pendingToolResults));
                            pendingToolResults.clear();
                        }
                        if ("user".equals(role)) {
                            conversation.addUserMessage(msg.content());
                        } else if ("assistant".equals(role)) {
                            conversation.addAssistantMessage(msg.content());
                        } else if ("system".equals(role)) {
                            // System messages from recovery (time-gap warnings, etc.)
                            conversation.addUserMessage("<system-reminder>\n"
                                    + msg.content() + "\n</system-reminder>");
                        }
                    }
                }
                // Flush any trailing tool_results
                if (!pendingToolResults.isEmpty()) {
                    conversation.addToolResultsMessage(new ArrayList<>(pendingToolResults));
                }
            }

            // Persist the resumed session ID so subsequent saves append
            if (resumeCallback != null) {
                resumeCallback.accept(selected.id());
            }

            writer.println("已恢复会话 " + selected.id()
                    + " (" + recovery.messages().size() + " 条消息)");
            if (recovery.needsCompaction()) {
                writer.println("⚠ 消息量较大，建议执行 /compact 压缩后再继续");
            }
            writer.flush();

        } catch (UserInterruptException e) {
            // cancelled
        } catch (Exception e) {
            writer.println("会话恢复失败: " + e.getMessage());
            writer.flush();
        }
    }

    // ── Tab completion ────────────────────────────────────────────────────

    /**
     * JLine Completer that provides Tab completion for slash commands.
     *
     * <p>When the input starts with {@code /}, searches the
     * {@link CommandRegistry} for matching non-hidden commands.
     * Single match → auto-completes with trailing space.
     * Multiple matches → populates the candidate list for JLine's menu.
     */
    private class SlashCommandCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line,
                             List<Candidate> candidates) {
            String input = line.line();
            if (input == null || !input.startsWith("/")) {
                return;
            }

            // Only complete when cursor is at/near end of the first word
            int cursor = Math.min(line.cursor(), input.length());
            String upToCursor = input.substring(0, cursor);

            // Only complete the command name (before first space)
            if (upToCursor.contains(" ")) {
                return;
            }

            String prefix = upToCursor.substring(1); // strip leading /
            if (cmdRegistry == null) {
                return;
            }

            List<Command> matches = cmdRegistry.search(prefix);
            for (Command cmd : matches) {
                candidates.add(new Candidate(cmd.name()));
            }
        }
    }
}

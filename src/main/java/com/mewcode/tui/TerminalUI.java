package com.mewcode.tui;

import com.mewcode.agent.AgentLoop;
import com.mewcode.conversation.ConversationManager;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * JLine-based interactive terminal UI loop.
 *
 * Handles slash commands (/exit, /clear, /plan, /do, /mode, /compact, /resume, /memory)
 * and delegates regular input to a pluggable handler for LLM processing.
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
    private AgentLoop agentLoop;
    private ConversationManager conversation;
    private MemoryManager memoryManager;
    private String workingDirectory;
    private Consumer<String> resumeCallback;

    public TerminalUI() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            writer = terminal.writer();
            lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
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

    /** Wire the agent loop for slash commands like /compact. */
    public void setAgentLoop(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
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

    /**
     * Start the read-eval-print loop.
     *
     * @param handler called with each non-command user input (on the calling thread)
     * @param onClear called when /clear is entered
     */
    public void start(InputHandler handler, Runnable onClear) {
        // Graceful cleanup on Ctrl+C / shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            writer.println("\nGoodbye!");
            writer.flush();
        }));

        writer.println("MewCode - 终端 AI 助手");
        writer.println("输入 /exit 退出, /clear 清空对话, /plan 调研模式, /do 执行模式, /mode 切换权限模式, /compact 压缩上下文, /resume 恢复会话, /memory 查看记忆");
        writer.println();
        writer.flush();

        while (true) {
            try {
                // Dynamic prompt with permission mode indicator
                String dynamicPrompt = PROMPT;
                if (permissionChecker != null) {
                    PermissionMode mode = permissionChecker.getMode();
                    String indicator = switch (mode) {
                        case DEFAULT       -> "";
                        case ACCEPT_EDITS  -> "\033[1;32m[接受编辑]\033[0m ";
                        case PLAN          -> "\033[1;33m[Plan]\033[0m ";
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

                // Slash commands
                if (input.startsWith("/")) {
                    switch (input) {
                        case "/exit" -> {
                            writer.println("Goodbye!");
                            writer.flush();
                            System.exit(0);
                            return;
                        }
                        case "/clear" -> {
                            onClear.run();
                            planMode = false;
                            if (permissionChecker != null) {
                                permissionChecker.setPlanMode(false);
                            }
                            writer.println("对话已清空");
                            writer.flush();
                        }
                        case "/plan" -> {
                            planMode = true;
                            if (permissionChecker != null) {
                                permissionChecker.setPlanMode(true);
                                permissionChecker.setMode(PermissionMode.PLAN);
                            }
                            writer.println("已进入 Plan Mode，仅可调研（只读工具）");
                            writer.flush();
                        }
                        case "/do" -> {
                            planMode = false;
                            if (permissionChecker != null) {
                                permissionChecker.setPlanMode(false);
                                permissionChecker.setMode(PermissionMode.DEFAULT);
                            }
                            writer.println("已退出 Plan Mode，恢复 DEFAULT 权限模式");
                            writer.flush();
                        }
                        case "/mode" -> {
                            if (permissionChecker != null) {
                                PermissionMode current = permissionChecker.getMode();
                                // Skip PLAN in normal cycling (PLAN is entered via /plan)
                                PermissionMode next = switch (current) {
                                    case DEFAULT       -> PermissionMode.ACCEPT_EDITS;
                                    case ACCEPT_EDITS  -> PermissionMode.BYPASS;
                                    case BYPASS        -> PermissionMode.DEFAULT;
                                    case PLAN          -> PermissionMode.DEFAULT;
                                };
                                permissionChecker.setMode(next);
                                String desc = switch (next) {
                                    case DEFAULT       -> "默认 — 读取自动放行，写入/命令需确认";
                                    case ACCEPT_EDITS  -> "接受编辑 — 读取/写入自动放行，命令需确认";
                                    case BYPASS        -> "YOLO — 全部自动放行（危险）";
                                    case PLAN          -> "Plan — 只读调研模式";
                                };
                                writer.println("权限模式 → " + desc);
                            } else {
                                writer.println("权限检查器未初始化");
                            }
                            writer.flush();
                        }
                        case "/compact" -> {
                            if (agentLoop != null) {
                                writer.println("正在压缩...");
                                writer.flush();
                                String result = agentLoop.forceCompact();
                                writer.println(result);
                            } else {
                                writer.println("Agent Loop 未初始化");
                            }
                            writer.flush();
                        }
                        case "/resume" -> {
                            handleResume();
                        }
                        case "/memory" -> {
                            handleMemory();
                        }
                        default -> {
                            writer.println("未知命令: " + input);
                            writer.flush();
                        }
                    }
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

    // ---- /resume command ----

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
                for (SessionManager.SessionMessage msg : recovery.messages()) {
                    String role = msg.role();
                    if ("user".equals(role)) {
                        conversation.addUserMessage(msg.content());
                    } else if ("assistant".equals(role)) {
                        conversation.addAssistantMessage(msg.content());
                    }
                    // Other roles are preserved but added as user messages for simplicity
                }
            }

            // Persist the resumed session ID so subsequent saves append
            // to the existing file instead of creating a new one.
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

    // ---- /memory command ----

    private void handleMemory() {
        if (memoryManager == null) {
            writer.println("记忆管理器未初始化");
            writer.flush();
            return;
        }

        List<MemoryEntry> entries = memoryManager.listAll();
        if (entries.isEmpty()) {
            writer.println("暂无自动记忆（完成几轮对话后会自动提取）");
            writer.flush();
            return;
        }

        writer.println();
        writer.println("════════ 自动记忆 (" + entries.size() + " 条) ════════");
        for (MemoryEntry entry : entries) {
            String typeLabel = switch (entry.type()) {
                case "user" -> "👤 用户";
                case "feedback" -> "💬 反馈";
                case "project" -> "📁 项目";
                case "reference" -> "🔗 参考";
                default -> "   " + entry.type();
            };
            writer.println(" [" + typeLabel + "] " + entry.description());
            writer.println("   文件: " + entry.name() + ".md");
        }
        writer.println("══════════════════════════════════════");
        writer.flush();
    }
}

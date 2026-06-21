package com.mewcode.command;

import com.mewcode.command.Command;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Central registry for all slash commands.
 *
 * <p>Commands are registered at startup. Alias conflicts are detected eagerly
 * and cause an immediate {@link System#exit(int)} with a clear error message.
 *
 * <p>Built-in commands are registered via {@link #registerDefaults()}, which is
 * called automatically from the constructor.
 */
public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();

    // ── Injectable callbacks ─────────────────────────────────────────────

    /** Called by /compact to trigger context compaction. */
    private Supplier<String> compactHandler;

    /** Called by /permission mode to switch the permission mode. */
    private Consumer<String> permissionModeHandler;

    // ── Public API ───────────────────────────────────────────────────────

    /** Creates a registry pre-populated with all built-in MewCode commands. */
    public CommandRegistry() {
        registerDefaults();
    }

    /**
     * Registers a command with an optional handler.
     *
     * <p>Alias conflict detection runs eagerly: if {@code cmd.name()} or any
     * alias in {@code cmd.aliases()} collides with an already-registered
     * command's name or alias (case-insensitive), the process exits immediately
     * with code 1 and a detailed error message.
     *
     * @param cmd     command definition
     * @param handler handler function ({@code args -> output}); may be
     *                {@code null} for UI-only commands
     */
    public void register(Command cmd, Function<CommandContext, String> handler) {
        // ── Conflict detection ────────────────────────────────────────
        String newLower = cmd.lowerName();
        for (var existing : commands) {
            if (existing.lowerName().equals(newLower)) {
                System.err.println("命令注册冲突: /" + cmd.name()
                        + " 的名称与已注册命令 /" + existing.name() + " 重复");
                System.exit(1);
            }
            for (var alias : cmd.aliases()) {
                String aliasLower = alias.toLowerCase(Locale.ROOT);
                if (existing.lowerName().equals(aliasLower)) {
                    System.err.println("命令注册冲突: /" + cmd.name()
                            + " 的别名 \"" + alias + "\" 与已注册命令 /"
                            + existing.name() + " 的名称重复");
                    System.exit(1);
                }
                for (var existingAlias : existing.aliases()) {
                    if (existingAlias.toLowerCase(Locale.ROOT).equals(aliasLower)) {
                        System.err.println("命令注册冲突: /" + cmd.name()
                                + " 的别名 \"" + alias + "\" 与已注册命令 /"
                                + existing.name() + " 的别名 \"" + existingAlias + "\" 重复");
                        System.exit(1);
                    }
                }
            }
        }

        commands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.lowerName(), handler);
            for (var alias : cmd.aliases()) {
                handlers.put(alias.toLowerCase(Locale.ROOT), handler);
            }
        }
    }

    /**
     * Finds a command by exact name or alias match (case-insensitive).
     */
    public Optional<Command> find(String name) {
        return commands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
    }

    /**
     * Returns all non-hidden commands whose name or alias starts with
     * {@code prefix} (case-insensitive), sorted by name.
     */
    public List<Command> search(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return commands.stream()
                .filter(c -> !c.hidden())
                .filter(c -> {
                    if (c.lowerName().startsWith(lower)) {
                        return true;
                    }
                    for (var alias : c.aliases()) {
                        if (alias.toLowerCase(Locale.ROOT).startsWith(lower)) {
                            return true;
                        }
                    }
                    return false;
                })
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    /**
     * Executes a LOCAL or PROMPT command handler and returns its output.
     *
     * @param name command name or alias (case-insensitive)
     * @param ctx  runtime context
     * @return handler output, or an error message if not found / no handler
     */
    public String execute(String name, CommandContext ctx) {
        String key = name.toLowerCase(Locale.ROOT);
        Function<CommandContext, String> handler = handlers.get(key);
        if (handler != null) {
            return handler.apply(ctx);
        }
        // Try canonical name
        Optional<Command> cmd = find(name);
        if (cmd.isPresent()) {
            handler = handlers.get(cmd.get().lowerName());
            if (handler != null) {
                return handler.apply(ctx);
            }
            return "No handler registered for /" + cmd.get().name();
        }
        return "Unknown command: /" + name;
    }

    /** Returns an unmodifiable view of all registered commands. */
    public List<Command> listAll() {
        return Collections.unmodifiableList(commands);
    }

    /** Returns all non-hidden commands, sorted by name. */
    public List<Command> listVisible() {
        return commands.stream()
                .filter(c -> !c.hidden())
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    // ── Callback setters ─────────────────────────────────────────────────

    /** Set the handler for /compact. */
    public void setCompactHandler(Supplier<String> handler) {
        this.compactHandler = handler;
    }

    /** Set the handler for /permission mode switching. */
    public void setPermissionModeHandler(Consumer<String> handler) {
        this.permissionModeHandler = handler;
    }

    // ── Built-in command registration ────────────────────────────────────

    private void registerDefaults() {

        // ── /help (LOCAL, aliases: h, ?) ─────────────────────────────
        register(new Command("help", "显示可用命令列表或单个命令详情",
                        new String[]{"h", "?"}, "/help [<command>]",
                        CommandType.LOCAL, "<命令名>", false),
                ctx -> {
                    String args = ctx.args();
                    if (args != null && !args.isBlank()) {
                        Optional<Command> target = find(args.strip());
                        if (target.isEmpty()) {
                            return "未知命令: " + args.strip()
                                    + " — 输入 /help 查看可用命令";
                        }
                        Command c = target.get();
                        var sb = new StringBuilder();
                        sb.append(c.usage()).append("\n");
                        sb.append("  ").append(c.description()).append("\n");
                        if (c.aliases().length > 0) {
                            sb.append("  别名: ")
                                    .append(String.join(", ", c.aliases()))
                                    .append("\n");
                        }
                        if (c.paramHint() != null && !c.paramHint().isBlank()) {
                            sb.append("  参数: ").append(c.paramHint()).append("\n");
                        }
                        return sb.toString();
                    }
                    var sb = new StringBuilder();
                    sb.append("可用命令:\n\n");
                    for (var cmd : listVisible()) {
                        String aliases = "";
                        if (cmd.aliases().length > 0) {
                            aliases = ", /" + String.join(", /", cmd.aliases());
                        }
                        String paramPart = cmd.paramHint() != null
                                && !cmd.paramHint().isBlank()
                                ? " " + cmd.paramHint() : "";
                        sb.append("  /").append(cmd.name())
                                .append(paramPart).append(aliases).append("\n");
                        sb.append("    ").append(cmd.description()).append("\n");
                    }
                    sb.append("\n输入 /help <命令名> 查看详细信息。");
                    return sb.toString();
                });

        // ── /status (LOCAL, alias: s) ────────────────────────────────
        register(new Command("status", "显示当前运行状态",
                        new String[]{"s"}, "/status",
                        CommandType.LOCAL, "", false),
                ctx -> {
                    var sb = new StringBuilder();
                    sb.append("MewCode 状态\n");
                    sb.append("────────────\n");
                    sb.append("  工作目录:   ").append(ctx.workDir()).append("\n");
                    sb.append("  模型:       ").append(ctx.model()).append("\n");
                    sb.append("  权限模式:   ").append(ctx.permissionMode().get())
                            .append("\n");
                    sb.append("  计划模式:   ")
                            .append(ctx.planMode().getAsBoolean() ? "PLAN"
                                    : "DEFAULT")
                            .append("\n");
                    int[] tokens = ctx.tokenCount().get();
                    sb.append("  Token:      输入 ")
                            .append(tokens[0]).append(" / 输出 ")
                            .append(tokens[1]).append("\n");
                    sb.append("  工具数量:    ")
                            .append(ctx.toolCount().getAsInt()).append(" 个\n");
                    var memories = ctx.memoryList().get();
                    sb.append("  记忆条数:    ")
                            .append(memories.size()).append(" 条\n");
                    return sb.toString();
                });

        // ── /compact (LOCAL, alias: c) ───────────────────────────────
        register(new Command("compact", "压缩对话上下文以节省 Token",
                        new String[]{"c"}, "/compact",
                        CommandType.LOCAL, "", false),
                ctx -> {
                    if (compactHandler != null) {
                        return compactHandler.get();
                    }
                    return "Agent Loop 未初始化，无法执行压缩";
                });

        // ── /session (LOCAL) ─────────────────────────────────────────
        register(new Command("session", "会话管理 (list / info)",
                        new String[]{}, "/session [list|info]",
                        CommandType.LOCAL, "list|info", false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank())
                            ? "list"
                            : args.strip().split("\\s+", 2)[0]
                                    .toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "list", "info" -> ctx.sessionInfo().get();
                        default -> "用法: /session [list|info]\n"
                                + "  list — 列出所有存档会话\n"
                                + "  info — 显示当前会话信息";
                    };
                });

        // ── /memory (LOCAL) ──────────────────────────────────────────
        register(new Command("memory", "管理自动记忆 (list / clear)",
                        new String[]{}, "/memory [list|clear]",
                        CommandType.LOCAL, "list|clear", false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank())
                            ? "list"
                            : args.strip().split("\\s+", 2)[0]
                                    .toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "list" -> {
                            var memories = ctx.memoryList().get();
                            if (memories.isEmpty()) {
                                yield "暂无自动记忆"
                                        + "（完成几轮对话后会自动提取）";
                            }
                            var sb = new StringBuilder(
                                    "自动记忆 (" + memories.size()
                                            + " 条):\n");
                            for (var m : memories) {
                                sb.append("  • ").append(m).append("\n");
                            }
                            yield sb.toString();
                        }
                        case "clear" -> {
                            ctx.memoryClear().run();
                            yield "已清除所有自动记忆";
                        }
                        default -> "用法: /memory [list|clear]\n"
                                + "  list  — 列出所有自动记忆\n"
                                + "  clear — 清除全部记忆";
                    };
                });

        // ── /permission (LOCAL, alias: perm) ─────────────────────────
        register(new Command("permission", "权限管理 (info / mode <模式>)",
                        new String[]{"perm"}, "/permission [info|mode <模式>]",
                        CommandType.LOCAL, "info|mode", false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank())
                            ? "info"
                            : args.strip().split("\\s+", 2)[0]
                                    .toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> "当前权限模式: "
                                + ctx.permissionMode().get();
                        case "mode" -> {
                            String rest = args.strip().substring(4).strip();
                            if (rest.isEmpty()) {
                                yield "用法: /permission mode "
                                        + "<default|acceptEdits|bypass>\n"
                                        + "当前模式: "
                                        + ctx.permissionMode().get();
                            }
                            if (permissionModeHandler != null) {
                                permissionModeHandler.accept(rest);
                                yield "权限模式已切换为: " + rest;
                            }
                            yield "权限检查器未初始化，无法切换模式";
                        }
                        default -> "用法: /permission [info|mode <模式>]\n"
                                + "  info      — 显示当前权限模式\n"
                                + "  mode <m>  — 切换模式 (default / acceptEdits / bypass)";
                    };
                });

        // ── /review (PROMPT) ─────────────────────────────────────────
        register(new Command("review", "审查当前代码变更",
                        new String[]{}, "/review [<关注点>]",
                        CommandType.PROMPT, "<关注点>", false),
                ctx -> {
                    String args = ctx.args();
                    String prompt = "Please review the current git diff "
                            + "for code changes. Focus on:\n"
                            + "1. Logic errors\n"
                            + "2. Security issues\n"
                            + "3. Performance problems\n"
                            + "4. Code style";
                    if (args != null && !args.isBlank()) {
                        prompt += "\n\nAdditional focus: "
                                + args.strip();
                    }
                    return prompt;
                });

        // ── LOCAL_UI commands (handler = null) ───────────────────────

        register(new Command("clear", "清空当前对话历史",
                        new String[]{}, "/clear",
                        CommandType.LOCAL_UI, "", false),
                null);

        register(new Command("plan", "进入计划（只读调研）模式",
                        new String[]{"p"}, "/plan",
                        CommandType.LOCAL_UI, "", false),
                null);

        register(new Command("do", "退出计划模式，恢复执行模式",
                        new String[]{}, "/do",
                        CommandType.LOCAL_UI, "", false),
                null);

        register(new Command("mode", "循环切换权限模式 (DEFAULT→ACCEPT_EDITS→BYPASS→DEFAULT)",
                        new String[]{}, "/mode",
                        CommandType.LOCAL_UI, "", false),
                null);

        register(new Command("exit", "退出 MewCode",
                        new String[]{"q"}, "/exit",
                        CommandType.LOCAL_UI, "", false),
                null);

        register(new Command("resume", "恢复之前的会话",
                        new String[]{"r"}, "/resume",
                        CommandType.LOCAL_UI, "", false),
                null);
    }
}

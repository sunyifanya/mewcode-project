package com.mewcode.permission;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Seven-layer defence chain for tool-call permission checks.
 *
 * <p>Layers (checked in order; first decision wins):
 * <ol start="0">
 *   <li><b>Plan mode exceptions</b> — auto-allow safe tools and plan-file writes.</li>
 *   <li><b>Safe commands</b> — auto-allow whitelisted, shell-meta-free commands.</li>
 *   <li><b>Dangerous commands</b> — hard-deny known dangerous patterns.</li>
 *   <li><b>Path sandbox</b> — reject file writes outside project root and /tmp.</li>
 *   <li><b>File-based rules</b> — YAML rules from user, project, and local configs.</li>
 *   <li><b>Session allow-always</b> — in-memory rules from "Yes, always" responses.</li>
 *   <li><b>Permission mode</b> — fallback via {@code PermissionMode.decide(ToolCategory)}.</li>
 * </ol>
 *
 * <p>Layer 6 produces {@link PermissionMode.Decision#ASK} when the mode requires
 * human-in-the-loop; the caller ({@code AgentLoop}) blocks and prompts the user.
 */
public class PermissionChecker {

    // ---- Dependencies ----
    private PermissionMode mode;
    private final PathSandbox sandbox;
    private final RuleEngine ruleEngine;
    private final Path projectRoot;  // for persisting allow-always rules

    // ---- Plan mode state ----
    private boolean planMode;

    // ---- Session-level allow-always rules ----
    private final Set<String> allowAlwaysRules = new HashSet<>();

    // ========================================================================
    // Layer 1: Safe command whitelist
    // ========================================================================

    /** Commands that are always safe to run (no side-effects or read-only). */
    private static final Set<String> SAFE_COMMANDS = Set.of(
            // File listing
            "ls", "dir", "tree",
            // File reading
            "cat", "head", "tail", "less", "more",
            // Path
            "pwd", "which", "whereis", "where", "realpath", "readlink",
            // System info
            "uname", "hostname", "whoami", "id", "date", "env", "printenv",
            "uptime", "arch",
            // Disk/memory (read-only)
            "df", "du", "free",
            // Process listing (read-only)
            "ps", "top", "htop", "pgrep",
            // Network (read-only)
            "ping", "traceroute", "nslookup", "dig", "host", "netstat", "ss",
            "ifconfig", "ip",
            // Git (read-only)
            "git",
            // File search
            "find", "locate", "fd",
            // Text search
            "grep", "rg", "egrep", "fgrep",
            // File info
            "file", "stat", "wc", "md5sum", "sha1sum", "sha256sum", "cksum",
            // Diff
            "diff", "cmp", "comm",
            // Text processing (read-only)
            "sort", "uniq", "cut", "tr", "paste", "join", "column", "fmt",
            "nl", "od", "xxd", "strings",
            // Misc
            "echo", "printf", "true", "false", "test", "[",
            // Package (query only)
            "dpkg-query", "rpm"
    );

    /**
     * Git subcommands that are safe (read-only or informational).
     * Other git subcommands (push, commit, etc.) are NOT safe.
     */
    private static final Set<String> SAFE_GIT_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "branch", "tag", "blame",
            "ls-files", "ls-tree", "rev-parse", "rev-list", "describe",
            "remote", "config", "stash", "reflog", "shortlog", "whatchanged",
            "for-each-ref", "name-rev", "symbolic-ref", "cat-file",
            "check-ignore", "check-attr", "notes", "worktree", "grep",
            "merge-base", "cherry"
    );

    // Shell metacharacters that indicate command chaining
    private static final Pattern SHELL_META = Pattern.compile(
            "[|;&]|&&|\\|\\||\\$\\(|`|>|>>|<|<<"
    );

    // ========================================================================
    // Layer 2: Dangerous command patterns
    // ========================================================================

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // Destructive file operations on root/system
            Pattern.compile("rm\\s+-rf\\s+/"),
            Pattern.compile("rm\\s+-rf\\s+~"),
            Pattern.compile("rm\\s+-rf\\s+\\$HOME"),
            Pattern.compile("rm\\s+-rf\\s+/boot"),
            Pattern.compile("rm\\s+-rf\\s+/etc"),
            Pattern.compile("rm\\s+-rf\\s+/usr"),
            Pattern.compile("rm\\s+-rf\\s+/var"),
            Pattern.compile("rm\\s+-rf\\s+/home"),
            Pattern.compile("rm\\s+-rf\\s+/opt"),
            // Filesystem manipulation
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+.*of=/dev/"),
            Pattern.compile("dd\\s+.*of=/dev/sd"),
            // Permissions escalation/changes
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile("chown\\s+-R\\s+.*\\s+/"),
            Pattern.compile("chmod\\s+777\\s+/"),
            // Fork bombs
            Pattern.compile(":\\(\\)\\s*\\{\\s*:|:&\\s*};:"),
            Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)\\s*;\\s*do\\b"),
            // Curl/wget piped to shell
            Pattern.compile("curl.*\\|.*(?:ba)?sh"),
            Pattern.compile("wget.*\\|.*(?:ba)?sh"),
            Pattern.compile("curl.*\\|.*bash"),
            // Writing to /dev/sd
            Pattern.compile(">\\s*/dev/sd"),
            // Systemctl dangerous operations
            Pattern.compile("systemctl\\s+(?:halt|poweroff|reboot|kexec|suspend|hibernate)"),
            // Shutdown/reboot
            Pattern.compile("\\b(?:shutdown|reboot|halt|poweroff)\\b"),
            // Formatting
            Pattern.compile("fdisk\\s+/dev/"),
            // Overwrite with dd
            Pattern.compile("dd\\s+if=/dev/(?:zero|random|urandom)\\s+of=/dev/")
    );

    // ========================================================================
    // Layer 0: Plan mode exceptions
    // ========================================================================

    // ========================================================================
    // describeToolAction — content field mapping
    // ========================================================================

    /** Maps tool names to their "key parameter" field for human-readable descriptions. */
    private static final Map<String, String> CONTENT_FIELDS = Map.ofEntries(
            Map.entry("execute_command", "command"),
            Map.entry("read_file", "file_path"),
            Map.entry("write_file", "file_path"),
            Map.entry("edit_file", "file_path"),
            Map.entry("glob", "pattern"),
            Map.entry("grep", "pattern")
    );

    // ---- Constructors ----

    public PermissionChecker(PermissionMode mode, PathSandbox sandbox, RuleEngine ruleEngine) {
        this.mode = mode;
        this.sandbox = sandbox;
        this.ruleEngine = ruleEngine;
        this.projectRoot = null;
    }

    /**
     * Convenience constructor that builds sandbox and rule engine from a project root.
     */
    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.sandbox = new PathSandbox(projectRoot);
        this.ruleEngine = new RuleEngine(PermissionConfig.load(projectRoot));
        this.projectRoot = projectRoot;
    }

    // ---- Mode management ----

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }

    public boolean isPlanMode() { return planMode; }
    public void setPlanMode(boolean planMode) { this.planMode = planMode; }

    public RuleEngine getRuleEngine() { return ruleEngine; }

    // ---- Main check API ----

    /**
     * Run the full defence chain for a tool call.
     *
     * @param tool the tool being called (for category lookup)
     * @param args the tool's parameters
     * @return ALLOW, DENY, or ASK (for human-in-the-loop)
     */
    public PermissionResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.getName();
        ToolCategory category = tool.category();
        String content = extractContent(toolName, args);

        // ---- Layer 1: Safe commands (auto-allow) ----
        if (isShellTool(toolName) && content != null) {
            if (isSafeCommand(content)) {
                return PermissionResult.allow("安全命令: 自动放行");
            }
            if (isDangerousCommand(content)) {
                return PermissionResult.deny(
                        "危险命令被拦截: " + truncate(content, 80),
                        "此命令可能造成不可逆的系统损坏");
            }
        }

        // ---- Layer 2: Path sandbox (file-mutating tools) ----
        if (category == ToolCategory.WRITE && content != null) {
            Path target = Paths.get(content);
            PermissionResult sandboxResult = sandbox.check(target);
            if (sandboxResult.isDeny()) {
                return sandboxResult;
            }
        }

        // ---- Layer 3: File-based permission rules ----
        if (content != null) {
            Optional<RuleEntry> matched = ruleEngine.match(toolName, content);
            if (matched.isPresent()) {
                RuleEntry rule = matched.get();
                if (rule.isAllow()) {
                    return PermissionResult.allow("规则放行: " + rule);
                } else {
                    return PermissionResult.deny(
                            "规则拦截: " + rule,
                            "请尝试其他操作方式");
                }
            }
        }

        // ---- Layer 4: Session allow-always rules ----
        if (content != null && isAllowAlways(toolName, content)) {
            return PermissionResult.allow("本会话已永久放行: " + toolName + " " + truncate(content, 60));
        }

        // ---- Layer 5: Permission mode fallback ----
        PermissionMode.Decision decision = mode.decide(category);
        return switch (decision) {
            case ALLOW -> PermissionResult.allow(mode + " 模式: 默认放行 " + category);
            case DENY  -> PermissionResult.deny(
                    mode + " 模式: 未命中任何放行规则",
                    "请切换模式或添加对应规则");
            case ASK   -> PermissionResult.ask(
                    describeToolAction(toolName, args),
                    "如果这是安全的操作，可以选择放行");
        };
    }

    /**
     * Backward-compatible check with separate toolName + isReadOnly.
     * Used by ToolExecutionStrategy when Tool object isn't available.
     */
    public PermissionResult check(String toolName, Map<String, Object> args, boolean readOnly) {
        ToolCategory category = readOnly ? ToolCategory.READ : ToolCategory.COMMAND;
        // Approximate: need tool category for proper checking
        // This is a backward-compat path; new code should use check(Tool, args)
        return check(new ToolStub(toolName, category), args);
    }

    // ---- Public helpers ----

    /**
     * Build a human-readable description of what a tool call will do.
     */
    public static String describeToolAction(String toolName, Map<String, Object> args) {
        String content = extractContent(toolName, args);
        String action = switch (toolName) {
            case "execute_command" -> "Execute";
            case "read_file"       -> "Read";
            case "write_file"      -> "Write";
            case "edit_file"       -> "Edit";
            case "glob"            -> "Glob search";
            case "grep"            -> "Grep search";
            default                -> "Run " + toolName;
        };
        if (content != null && !content.isBlank() && !content.equals(toolName)) {
            return action + ": " + truncate(content, 120);
        }
        return action;
    }

    /**
     * Extract the key parameter from tool arguments for matching and display.
     * For tools with no known content field (e.g. MCP tools), returns the
     * tool name itself as fallback so rules can still be created and matched.
     */
    public static String extractContent(String toolName, Map<String, Object> args) {
        if (args == null) return toolName;
        String field = CONTENT_FIELDS.getOrDefault(toolName, null);
        if (field != null) {
            Object val = args.get(field);
            return val != null ? val.toString() : null;
        }
        // Generic fallback for unknown tools (MCP tools etc.):
        // use the tool name itself as the content key, so that
        // "always allow" rules apply at the tool level.
        return toolName;
    }

    /**
     * Add a session-scoped "allow always" rule. Does NOT persist to disk —
     * "always" means for the lifetime of this session only.
     */
    public void addAllowAlways(String toolName, String content) {
        String effectiveContent = (content != null && !content.isBlank()) ? content : toolName;
        allowAlwaysRules.add(toolName + "\0" + effectiveContent);
        ruleEngine.addAllowAlways(toolName, effectiveContent);
    }

    // ---- Private helpers ----

    private static final Set<String> SHELL_TOOL_NAMES = Set.of("execute_command");

    private boolean isShellTool(String toolName) {
        return SHELL_TOOL_NAMES.contains(toolName);
    }

    /**
     * Check if a shell command is safe to auto-allow.
     *
     * Rules: (1) Must not contain shell metacharacters, (2) base command
     * must be in the safe whitelist, (3) for "git" the subcommand must also be safe.
     */
    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();

        // Reject empty
        if (trimmed.isEmpty()) return false;

        // Reject if contains shell metacharacters
        if (SHELL_META.matcher(trimmed).find()) return false;

        // Split into tokens
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) return false;

        // Get base command (strip path)
        String base = tokens[0];
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash >= 0) {
            base = base.substring(lastSlash + 1);
        }

        // Check whitelist
        if (!SAFE_COMMANDS.contains(base)) return false;

        // Special handling for git
        if ("git".equals(base) && tokens.length >= 2) {
            // Skip flags (--git-dir, -C, etc.) to find subcommand
            String sub = null;
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].startsWith("-")) {
                    sub = tokens[i];
                    break;
                }
            }
            return sub == null || SAFE_GIT_SUBCOMMANDS.contains(sub);
        }

        return true;
    }

    /**
     * Check if a command matches any dangerous pattern.
     */
    private boolean isDangerousCommand(String command) {
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }


    /**
     * Check if a (toolName, content) pair is in the allow-always set.
     */
    private boolean isAllowAlways(String toolName, String content) {
        return allowAlwaysRules.contains(toolName + "\0" + content);
    }

    // ---- Utility ----

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Minimal Tool implementation for backward-compatible check() calls.
     */
    private record ToolStub(String name, ToolCategory category) implements Tool {
        @Override public String getName() { return name; }
        @Override public String getDescription() { return ""; }
        @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
        @Override public ToolResult execute(Map<String, Object> params) { return null; }
        @Override public boolean isReadOnly() { return category == ToolCategory.READ; }
        @Override public ToolCategory category() { return category; }
    }
}

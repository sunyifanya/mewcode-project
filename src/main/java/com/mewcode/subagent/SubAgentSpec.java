package com.mewcode.subagent;

import java.util.List;

/**
 * Defines the configuration for a sub-agent type, including its name,
 * description, tool restrictions, optional system prompt override,
 * maximum turns, model selection, permission mode, and background flag.
 *
 * <p>Sources (in priority order): project-level, user-level, built-in.</p>
 */
public record SubAgentSpec(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String systemPromptOverride,
        int maxTurns,
        String model,
        String permissionMode,
        boolean background
) {

    // ── Built-in fallback values ──────────────────────────────────────────

    /** Valid model aliases. */
    public static final List<String> VALID_MODELS = List.of("inherit", "haiku", "sonnet", "opus");

    /** Valid permission modes. */
    public static final List<String> VALID_PERMISSION_MODES = List.of(
            "default", "acceptEdits", "plan", "bypassPermissions", "dontAsk");

    // ── Built-in specs ────────────────────────────────────────────────────

    public static final SubAgentSpec GENERAL_PURPOSE = new SubAgentSpec(
            "general-purpose",
            "通用子 Agent，拥有完整工具访问权限，适合多步骤研究和实现任务",
            List.of(),
            List.of(),
            null,
            30,
            null,
            null,
            false
    );

    public static final SubAgentSpec EXPLORE = new SubAgentSpec(
            "explore",
            "只读搜索 Agent，快速定位代码文件和符号",
            List.of(),
            List.of("write_file", "edit_file"),
            null,
            30,
            "haiku",
            null,
            false
    );

    public static final SubAgentSpec PLAN = new SubAgentSpec(
            "plan",
            "软件架构规划 Agent，设计实现计划。返回分步计划，识别关键文件，考虑架构权衡。",
            List.of(),
            List.of("Agent", "write_file", "edit_file"),
            null,
            15,
            null,
            "plan",
            false
    );

    /**
     * Internal spec for Fork path. Not exposed in the subagent_type enum.
     * Fork unconditionally runs in background, inherits the parent's full
     * tool set, and is NOT subject to the async whitelist.
     */
    public static final SubAgentSpec FORK = new SubAgentSpec(
            "_fork",
            "Forked worker process",
            List.of(),
            List.of(),
            null,
            50,
            "inherit",
            "default",
            true    // Fork 强制后台
    );

    // ── Factory for loading ───────────────────────────────────────────────

    /**
     * Create a spec from raw frontmatter fields, filling in defaults.
     *
     * @param name              required role name
     * @param description       required one-line description
     * @param tools             nullable whitelist (empty = no restriction)
     * @param disallowedTools   nullable blacklist
     * @param systemPromptOverride nullable Markdown body (system prompt)
     * @param maxTurns          0 means "inherit global default"
     * @param model             nullable; "" or "inherit" treated as null
     * @param permissionMode    nullable; "" treated as null
     * @param background        whether to force background execution
     */
    public static SubAgentSpec of(
            String name, String description,
            List<String> tools, List<String> disallowedTools,
            String systemPromptOverride,
            int maxTurns, String model, String permissionMode,
            boolean background
    ) {
        List<String> effTools = tools != null ? tools : List.of();
        List<String> effDisallowed = disallowedTools != null ? disallowedTools : List.of();
        String effModel = (model == null || model.isEmpty() || "inherit".equals(model)) ? null : model;
        String effPermMode = (permissionMode == null || permissionMode.isEmpty()) ? null : permissionMode;
        return new SubAgentSpec(name, description, effTools, effDisallowed,
                (systemPromptOverride == null || systemPromptOverride.isEmpty()) ? null : systemPromptOverride,
                maxTurns, effModel, effPermMode, background);
    }

    // ── Convenience queries ───────────────────────────────────────────────

    public int effectiveMaxTurns(int globalDefault) {
        return maxTurns > 0 ? maxTurns : globalDefault;
    }

    public String effectiveModel() {
        return model != null ? model : "inherit";
    }

    public String effectivePermissionMode() {
        return permissionMode != null ? permissionMode : "default";
    }
}

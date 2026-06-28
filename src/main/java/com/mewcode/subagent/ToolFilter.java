package com.mewcode.subagent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Filters tool names to produce a restricted set suitable for a sub-agent.
 */
public final class ToolFilter {

    /** Tools that are never available to definition-based sub-agents. */
    static final Set<String> ALL_AGENT_DISALLOWED_TOOLS = Set.of("Agent");

    /** Reserved for future use — custom-agent specific blocks. */
    static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS = Set.of();

    /** Tools permitted for async (background) definition-based sub-agents. */
    static final Set<String> ASYNC_ALLOWED_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "glob", "grep",
            "execute_command", "Skill", "ToolSearch"
    );

    private ToolFilter() {}

    /**
     * Build a tool-name predicate for a sub-agent.
     *
     * @param spec    the sub-agent specification
     * @param isAsync whether this is a background/async execution
     * @param isFork  whether this is a fork (skips global-disallow + async-whitelist)
     * @return a predicate that returns true for allowed tool names
     */
    public static Predicate<String> buildFilter(SubAgentSpec spec, boolean isAsync, boolean isFork) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());

        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().getFirst()));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        return name -> {
            // Layer 1: MCP tools always pass through
            if (isMcpTool(name)) {
                return !disallowed.contains(name); // unless explicitly blacklisted
            }

            // Layer 2: Globally blocked tools (definition-based only)
            if (!isFork && ALL_AGENT_DISALLOWED_TOOLS.contains(name)) {
                return false;
            }

            // Layer 3: Async whitelist (definition-based background only; Fork skips)
            if (isAsync && !isFork) {
                if (!ASYNC_ALLOWED_TOOLS.contains(name) && !isMcpTool(name)) {
                    return false;
                }
            }

            // Layer 4: Per-spec disallowed tools
            if (disallowed.contains(name)) {
                return false;
            }

            // Layer 5: Per-spec whitelist intersection
            return !hasWhitelist || allowed.contains(name);
        };
    }

    /**
     * Build a filter for a definition-based foreground sub-agent.
     */
    public static Predicate<String> buildFilter(SubAgentSpec spec) {
        return buildFilter(spec, false, false);
    }

    /**
     * Build a filter for a fork sub-agent (always background, inherits parent tools).
     */
    public static Predicate<String> buildForkFilter() {
        return buildFilter(SubAgentSpec.FORK, true, true);
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}

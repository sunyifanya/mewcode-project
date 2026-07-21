package com.mewcode.permission;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Matches tool calls against user-configured and session-temporary rules.
 */
public class RuleEngine {

    private final List<RuleEntry> configRules;
    private final List<RuleEntry> sessionRules = new ArrayList<>();
    // Loop rules are cleared per AgentLoop
    private final List<RuleEntry> loopRules = new ArrayList<>();

    public RuleEngine(List<RuleEntry> configRules) {
        this.configRules = new ArrayList<>(configRules);
    }

    // ---- Rule management ----

    /** Add a rule that lasts for the current session (process lifetime). */
    public void addSessionRule(RuleEntry entry) {
        sessionRules.add(entry);
    }

    /** Add a rule that lasts for the current AgentLoop only. */
    public void addLoopRule(RuleEntry entry) {
        loopRules.add(entry);
    }

    /** Discard all loop-scoped rules (called at the start of each AgentLoop). */
    public void clearLoopRules() {
        loopRules.clear();
    }

    /**
     * Escape glob special characters so the target is matched literally.
     * Backslashes, *, ?, and [ are escaped.
     */
    public static String escapeGlob(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[");
    }

    /**
     * Add a persistent allow-always rule to session rules.
     * The pattern is the exact key parameter value (matched as glob).
     */
    public void addAllowAlways(String toolName, String content) {
        sessionRules.add(new RuleEntry(toolName, escapeGlob(content), RuleEntry.RuleEffect.ALLOW));
    }

    // ---- Matching ----

    /**
     * Match a tool call against all rule tiers.
     *
     * @param toolName tool name (e.g. "execute_command", "write_file")
     * @param target   the key parameter to match against (command string or file path)
     * @return the first matching rule (last-match-wins within each tier), or empty
     */
    public Optional<RuleEntry> match(String toolName, String target) {
        // 1. Loop rules (highest priority — "本次放行")
        Optional<RuleEntry> hit = matchListReverse(loopRules, toolName, target);
        if (hit.isPresent()) return hit;

        // 2. Session rules ("本会话放行")
        hit = matchListReverse(sessionRules, toolName, target);
        if (hit.isPresent()) return hit;

        // 3. Config rules (~/.mewcode/permissions.yaml)
        return matchListReverse(configRules, toolName, target);
    }

    /**
     * Iterate rules in reverse order so the last matching rule wins.
     */
    private Optional<RuleEntry> matchListReverse(List<RuleEntry> rules, String toolName, String target) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            RuleEntry rule = rules.get(i);
            if (!rule.getToolName().equals(toolName)) continue;
            if (target == null || target.isBlank()) continue;
            if (matchGlob(rule.getPattern(), target)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    // ---- Glob matching ----

    static boolean matchGlob(String pattern, String target) {
        String syntax = "glob:" + pattern;
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntax);
            return matcher.matches(java.nio.file.Paths.get(target));
        } catch (Exception e) {
            return false;
        }
    }
}

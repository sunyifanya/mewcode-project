package com.mewcode.permission;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single permission rule, either loaded from YAML or created at runtime.
 *
 * <p>YAML format (new):
 * <pre>{@code
 * rules:
 *   - rule: "Bash(git *)"
 *     effect: allow
 *   - rule: "WriteFile(/tmp/**)"
 *     effect: deny
 * }</pre>
 *
 * <p>The {@code rule} string is parsed as {@code ToolName(glob_pattern)}.
 * Runtime-created rules (from ALLOW_ALWAYS responses) use the programmatic constructor.
 */
public class RuleEntry {

    // ---- Parsed fields ----
    private String toolName;
    private String pattern;
    private RuleEffect effect;

    /** Allow or deny. */
    public enum RuleEffect {
        ALLOW, DENY;

        public static RuleEffect fromString(String s) {
            if (s == null || s.isBlank()) return DENY;
            return switch (s.trim().toLowerCase()) {
                case "allow" -> ALLOW;
                default -> DENY;
            };
        }
    }

    // ---- YAML deserialization fields (Jackson needs non-final + setters) ----

    @JsonProperty("rule")
    private String ruleRaw;

    @JsonProperty("effect")
    private String effectRaw;

    /** For Jackson deserialization. */
    public RuleEntry() {}

    /** Programmatic constructor for runtime-created rules (ALLOW_ALWAYS). */
    public RuleEntry(String toolName, String pattern, RuleEffect effect) {
        this.toolName = toolName;
        this.pattern = pattern;
        this.effect = effect;
    }

    /**
     * Called after YAML deserialization to parse {@code rule: "ToolName(pattern)"}.
     * Malformed entries are left with null toolName (caller should skip them).
     */
    public void resolve() {
        if (ruleRaw == null || ruleRaw.isBlank()) return;

        String[] parsed = parseRuleString(ruleRaw);
        if (parsed != null) {
            this.toolName = parsed[0];
            this.pattern = parsed[1];
        }
        this.effect = RuleEffect.fromString(effectRaw);
    }

    /**
     * Parse a "ToolName(pattern)" string into (toolName, pattern) pair.
     * Returns null if malformed.
     */
    public static String[] parseRuleString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        int parenOpen = trimmed.indexOf('(');
        int parenClose = trimmed.lastIndexOf(')');
        if (parenOpen <= 0 || parenClose != trimmed.length() - 1) return null;
        String tool = trimmed.substring(0, parenOpen).trim();
        String pat = trimmed.substring(parenOpen + 1, parenClose).trim();
        if (tool.isEmpty() || pat.isEmpty()) return null;
        return new String[]{tool, pat};
    }

    // ---- Jackson getters/setters ----

    public String getRuleRaw() { return ruleRaw; }
    public void setRuleRaw(String ruleRaw) { this.ruleRaw = ruleRaw; }

    public String getEffectRaw() { return effectRaw; }
    public void setEffectRaw(String effectRaw) { this.effectRaw = effectRaw; }

    // ---- Getters ----

    public String getToolName() { return toolName; }
    public String getPattern() { return pattern; }

    @JsonIgnore
    public RuleEffect getEffect() { return effect; }

    public boolean isAllow() { return effect == RuleEffect.ALLOW; }

    @Override
    public String toString() {
        return toolName + "(" + pattern + ") → " + effect;
    }
}

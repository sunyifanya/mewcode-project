package com.mewcode.permission;

import com.mewcode.tool.ToolCategory;

/**
 * Global permission mode that defines the default behaviour when no
 * explicit rule matches a tool call.
 *
 * <p>Inspired by Claude Code's permission modes:
 * <ul>
 *   <li><b>DEFAULT</b> — auto-allow READ, ask for WRITE and COMMAND.</li>
 *   <li><b>ACCEPT_EDITS</b> — auto-allow READ and WRITE, ask for COMMAND.</li>
 *   <li><b>PLAN</b> — delegates to DEFAULT (plus plan-mode exceptions in PermissionChecker).</li>
 *   <li><b>BYPASS</b> — auto-allow everything ("YOLO mode").</li>
 * </ul>
 */
public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    /**
     * Decision a permission layer can produce.
     */
    public enum Decision {
        /** Execute the tool call immediately, skip remaining layers. */
        ALLOW,
        /** Reject the tool call immediately, skip remaining layers. */
        DENY,
        /** This layer cannot decide — pass to the next layer. */
        ASK
    }

    /**
     * Decide the default action for a tool category under this mode.
     * This is the bottom-layer fallback when no rules match.
     */
    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    /**
     * Parse a mode string, falling back to DEFAULT for unrecognised input.
     *
     * <p>Backward compatibility:
     * <ul>
     *   <li>{@code "strict"} → DEFAULT (with stderr warning)</li>
     *   <li>{@code "permissive"} → BYPASS (with stderr warning)</li>
     * </ul>
     */
    public static PermissionMode fromString(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        return switch (s.trim().toLowerCase()) {
            case "default"                              -> DEFAULT;
            case "accept-edits", "accept_edits"         -> ACCEPT_EDITS;
            case "plan"                                 -> PLAN;
            case "bypass", "yolo"                       -> BYPASS;
            // backward compat
            case "strict" -> {
                System.err.println("警告: permission.mode 'strict' 已废弃，回退为 'default'");
                yield DEFAULT;
            }
            case "permissive" -> {
                System.err.println("警告: permission.mode 'permissive' 已废弃，回退为 'bypass'");
                yield BYPASS;
            }
            default -> {
                System.err.println("警告: 未知的 permission.mode '" + s + "'，回退为 'default'");
                yield DEFAULT;
            }
        };
    }
}

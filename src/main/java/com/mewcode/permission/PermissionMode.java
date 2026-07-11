package com.mewcode.permission;

import com.mewcode.tool.ToolCategory;

/**
 * Global permission mode that defines the default behavior when no
 * explicit rule matches a tool call.
 *
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
     */
    public static PermissionMode fromString(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        return switch (s.trim().toLowerCase()) {
            case "default"              -> DEFAULT;
            case "accept-edits", "accept_edits" -> ACCEPT_EDITS;
            case "plan"                 -> PLAN;
            case "bypass", "yolo"       -> BYPASS;
            default                     -> DEFAULT;
        };
    }
}

package com.mewcode.hook;

/**
 * Returned by {@link HookEngine#runPreToolHooks} to indicate whether a tool call
 * should be rejected before execution.
 *
 * @param rejected true if the tool should be blocked
 * @param message  the rejection reason (fed back to the model as tool result)
 */
public record PreToolResult(
        boolean rejected,
        String message
) {
    /** A pass-through result: tool is allowed to proceed. */
    public static final PreToolResult ALLOW = new PreToolResult(false, "");

    /** Convenience: create a rejection result. */
    public static PreToolResult reject(String message) {
        return new PreToolResult(true, message);
    }
}

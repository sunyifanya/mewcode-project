package com.mewcode.hook;

import java.util.Map;

/**
 * Runtime context passed to hook conditions and template rendering.
 * All fields are nullable — callers should guard with null checks.
 *
 * @param event     the event that triggered this hook invocation
 * @param toolName  tool name (only for pre_tool_use / post_tool_use)
 * @param toolArgs  tool input parameters (only for pre_tool_use / post_tool_use)
 * @param filePath  current file path, if applicable
 * @param message   message content (pre_send / post_receive / error messages)
 * @param error     error message (post_tool_use on failure, etc.)
 */
public record HookContext(
        EventName event,
        String toolName,
        Map<String, Object> toolArgs,
        String filePath,
        String message,
        String error
) {
    /** Convenience: create a minimal context with just the event. */
    public static HookContext of(EventName event) {
        return new HookContext(event, null, null, null, null, null);
    }

    /** Convenience: create a tool-event context. */
    public static HookContext ofTool(EventName event, String toolName, Map<String, Object> toolArgs) {
        return new HookContext(event, toolName, toolArgs, null, null, null);
    }

    /** Convenience: create a context with a message. */
    public static HookContext ofMessage(EventName event, String message) {
        return new HookContext(event, null, null, null, message, null);
    }
}

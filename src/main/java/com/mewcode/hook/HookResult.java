package com.mewcode.hook;

/**
 * Result of executing a single hook action.
 *
 * @param hookId  the hook's id (for logging / debugging)
 * @param output  stdout + stderr (command), rendered message (prompt), or response body (http)
 * @param success true if the action completed without error (exit code 0, HTTP 2xx, etc.)
 * @param reject  true if this hook's reject flag was set (pre_tool_use interception)
 */
public record HookResult(
        String hookId,
        String output,
        boolean success,
        boolean reject
) {}

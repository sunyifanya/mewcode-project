package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The action to execute when a hook triggers.
 *
 * <p>Exactly one type-specific payload is required:
 * <ul>
 *   <li>{@code command} — {@code command}, {@code timeout} (default 30), {@code background} (default false)</li>
 *   <li>{@code prompt}  — {@code message}</li>
 *   <li>{@code http}    — {@code url}, {@code method} (default GET), {@code headers}, {@code body}</li>
 *   <li>{@code sub_agent} — placeholder (not yet implemented)</li>
 * </ul>
 */
public record HookAction(
        @JsonProperty("type") ActionType type,

        // command
        @JsonProperty("command") String command,
        @JsonProperty("timeout") int timeout,
        @JsonProperty("background") boolean background,

        // prompt
        @JsonProperty("message") String message,

        // http
        @JsonProperty("url") String url,
        @JsonProperty("method") String method,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("body") String body
) {
    /** Default constructor for Jackson — sets safe defaults. */
    public HookAction {
        if (method == null || method.isBlank()) method = "GET";
        if (timeout <= 0) timeout = 30;
    }

    /** Convenience: true if this is a command-type action. */
    public boolean isCommand() { return type == ActionType.COMMAND; }

    /** Convenience: true if this is a prompt-type action. */
    public boolean isPrompt() { return type == ActionType.PROMPT; }

    /** Convenience: true if this is an http-type action. */
    public boolean isHttp() { return type == ActionType.HTTP; }

    /** Convenience: true if this is a sub_agent-type action. */
    public boolean isSubAgent() { return type == ActionType.SUB_AGENT; }
}

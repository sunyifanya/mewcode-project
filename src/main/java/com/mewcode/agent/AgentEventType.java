package com.mewcode.agent;

/**
 * Types of events the Agent Loop pushes to the UI via BlockingQueue.
 */
public enum AgentEventType {
    /** A text or thinking delta from the LLM stream. */
    TEXT_DELTA,
    /** A tool call has started (before execution). */
    TOOL_CALL_START,
    /** A tool call has completed with a result. */
    TOOL_CALL_RESULT,
    /** Token usage for the last LLM call (accumulated totals). */
    TOKEN_USAGE,
    /** Human-readable progress message (e.g. "Round 3/25"). */
    PROGRESS,
    /** A new user request's agent loop has begun. */
    LOOP_STARTED,
    /** Agent loop finished normally (model said end_turn). */
    LOOP_FINISHED,
    /** Agent loop terminated by an error. */
    ERROR,
    /** Agent loop was cancelled by the user. */
    CANCELLED,
    /** An unknown tool name was encountered; loop stops. */
    UNKNOWN_TOOL,
    /** A tool call needs user permission confirmation (blocking). */
    PERMISSION_REQUIRED,
    /** User responded to a PERMISSION_REQUIRED event. */
    PERMISSION_RESPONSE,
    /** Context compaction occurred (Layer 1 or Layer 2). */
    COMPACT,
    /** Memory extraction tick — model stopped without tool calls, async extraction may run. */
    MEMORY_TICK
}

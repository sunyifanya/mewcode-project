package com.mewcode.agent;

import com.mewcode.permission.PermissionResponse;
import com.mewcode.provider.ChunkType;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolResult;

/**
 * Immutable event pushed from the Agent Loop to the UI consumer thread.
 *
 * Fields are populated selectively based on type — check {@link #type} before
 * reading any field that may be null for that event kind.
 */
public final class AgentEvent {

    private final AgentEventType type;

    // TEXT_DELTA
    private final String text;
    private final ChunkType chunkType;

    // TOOL_CALL_START / TOOL_CALL_RESULT / UNKNOWN_TOOL / PERMISSION_REQUIRED
    private final String toolName;
    private final String callId;

    // TOOL_CALL_RESULT
    private final ToolResult toolResult;

    // TOKEN_USAGE
    private final int inputTokens;
    private final int outputTokens;

    // TOKEN_USAGE — cache metrics (-1 = no data from provider)
    private final long cacheCreationTokens;
    private final long cacheReadTokens;

    // PROGRESS / ERROR
    private final String message;

    // PERMISSION_REQUIRED
    private final ToolCall permissionToolCall;

    // PERMISSION_RESPONSE
    private final PermissionResponse permissionResponse;

    private AgentEvent(Builder builder) {
        this.type = builder.type;
        this.text = builder.text;
        this.chunkType = builder.chunkType;
        this.toolName = builder.toolName;
        this.callId = builder.callId;
        this.toolResult = builder.toolResult;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.cacheCreationTokens = builder.cacheCreationTokens;
        this.cacheReadTokens = builder.cacheReadTokens;
        this.message = builder.message;
        this.permissionToolCall = builder.permissionToolCall;
        this.permissionResponse = builder.permissionResponse;
    }

    // -- getters --

    public AgentEventType getType() { return type; }
    public String getText() { return text; }
    public ChunkType getChunkType() { return chunkType; }
    public String getToolName() { return toolName; }
    public String getCallId() { return callId; }
    public ToolResult getToolResult() { return toolResult; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public long getCacheCreationTokens() { return cacheCreationTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public String getMessage() { return message; }
    public ToolCall getPermissionToolCall() { return permissionToolCall; }
    public PermissionResponse getPermissionResponse() { return permissionResponse; }

    // -- builder --

    public static Builder of(AgentEventType type) {
        return new Builder(type);
    }

    /** Convenience: create a COMPACT event with a log message. */
    public static AgentEvent compactEvent(String message) {
        return new Builder(AgentEventType.COMPACT).message(message).build();
    }

    public static final class Builder {
        private final AgentEventType type;
        private String text;
        private ChunkType chunkType;
        private String toolName;
        private String callId;
        private ToolResult toolResult;
        private int inputTokens;
        private int outputTokens;
        private long cacheCreationTokens = -1;
        private long cacheReadTokens = -1;
        private String message;
        private ToolCall permissionToolCall;
        private PermissionResponse permissionResponse;

        private Builder(AgentEventType type) {
            this.type = type;
        }

        public Builder text(String text) { this.text = text; return this; }
        public Builder chunkType(ChunkType chunkType) { this.chunkType = chunkType; return this; }
        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder callId(String callId) { this.callId = callId; return this; }
        public Builder toolResult(ToolResult toolResult) { this.toolResult = toolResult; return this; }
        public Builder inputTokens(int inputTokens) { this.inputTokens = inputTokens; return this; }
        public Builder outputTokens(int outputTokens) { this.outputTokens = outputTokens; return this; }
        public Builder cacheCreationTokens(long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; return this; }
        public Builder cacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder permissionToolCall(ToolCall permissionToolCall) { this.permissionToolCall = permissionToolCall; return this; }
        public Builder permissionResponse(PermissionResponse permissionResponse) { this.permissionResponse = permissionResponse; return this; }

        public AgentEvent build() {
            return new AgentEvent(this);
        }
    }

    @Override
    public String toString() {
        return "AgentEvent{type=" + type + "}";
    }
}

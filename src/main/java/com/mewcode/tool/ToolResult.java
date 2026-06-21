package com.mewcode.tool;

/**
 * Structured result returned by every tool execution.
 * Failures are encoded as success=false rather than thrown exceptions,
 * so the model can see the error and adjust.
 */
public class ToolResult {

    private final boolean success;
    private final String content;
    private final String errorCode;

    public ToolResult(boolean success, String content, String errorCode) {
        this.success = success;
        this.content = content;
        this.errorCode = errorCode;
    }

    /** Convenience: successful result with no error code. */
    public ToolResult(boolean success, String content) {
        this(success, content, null);
    }

    public boolean isSuccess() { return success; }
    public String getContent() { return content; }
    public String getErrorCode() { return errorCode; }

    @Override
    public String toString() {
        return success ? content : "[错误" + (errorCode != null ? " " + errorCode : "") + "] " + content;
    }
}

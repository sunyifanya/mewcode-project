package com.mewcode.conversation;

import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * A single message in the conversation history.
 *
 * Supports both legacy single-result and new block-level tool-use / tool-result
 * representations. The block-level fields ({@code thinkingBlocks}, {@code toolUses},
 * {@code toolResults}) are the primary storage; the legacy single-result fields
 * delegate to the first element of the corresponding list.
 */
public class Message {

    private String role;
    private String content;

    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;

    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // ── Role & Content ─────────────────────────────────────────────────

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    // ── Thinking blocks ─────────────────────────────────────────────────

    public List<ThinkingBlock> getThinkingBlocks() { return thinkingBlocks; }
    public void setThinkingBlocks(List<ThinkingBlock> thinkingBlocks) {
        this.thinkingBlocks = thinkingBlocks;
    }

    // ── Tool use blocks (primary) ───────────────────────────────────────

    public List<ToolUseBlock> getToolUses() { return toolUses; }
    public void setToolUses(List<ToolUseBlock> toolUses) {
        this.toolUses = toolUses;
    }

    // ── Tool result blocks (primary) ────────────────────────────────────

    public List<ToolResultBlock> getToolResults() { return toolResults; }
    public void setToolResults(List<ToolResultBlock> toolResults) {
        this.toolResults = toolResults;
    }

    /**
     * Convenience: true when this message carries at least one error tool_result.
     */
    public boolean hasErrorResult() {
        if (toolResults == null) return false;
        for (ToolResultBlock tr : toolResults) {
            if (tr.isError()) return true;
        }
        return false;
    }

    // ── Factory methods ─────────────────────────────────────────────────

    /** Create an assistant message with tool_use blocks. */
    public static Message toolCallMessage(String textContent, List<ToolCall> toolCalls) {
        Message msg = new Message("assistant", textContent);
        List<ToolUseBlock> blocks = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            blocks.add(new ToolUseBlock(tc.getId(), tc.getName(), tc.getInput()));
        }
        msg.toolUses = blocks;
        // legacy bridge
        msg.toolCalls = toolCalls;
        return msg;
    }

    /** Create a user message carrying a single tool_result block. */
    public static Message toolResultMessage(String toolUseId, ToolResult result) {
        Message msg = new Message("user", result.getContent());
        msg.toolResults = List.of(new ToolResultBlock(toolUseId, result.getContent(), !result.isSuccess()));
        // legacy bridge
        msg.toolUseId = toolUseId;
        msg.toolResult = result;
        return msg;
    }

    /** Create a user message carrying multiple tool_result blocks. */
    public static Message toolResultsMessage(List<ToolResultBlock> results) {
        Message msg = new Message("user", "");
        msg.toolResults = List.copyOf(results);
        // Legacy bridge: point to first result if present
        if (!results.isEmpty()) {
            msg.toolUseId = results.get(0).toolUseId();
            msg.toolResult = new ToolResult(!results.get(0).isError(), results.get(0).content());
        }
        return msg;
    }

    // ── Legacy fields (backward compat) ─────────────────────────────────

    /** @deprecated Use {@link #getToolUses()} instead. */
    @Deprecated
    private List<ToolCall> toolCalls;

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    private String toolUseId;

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    private ToolResult toolResult;

    /** @deprecated Use {@link #getToolUses()} instead. */
    @Deprecated
    public List<ToolCall> getToolCalls() {
        if (toolCalls != null) return toolCalls;
        if (toolUses == null || toolUses.isEmpty()) return null;
        // Convert on the fly
        List<ToolCall> converted = new ArrayList<>();
        for (ToolUseBlock tu : toolUses) {
            ToolCall tc = new ToolCall();
            tc.setId(tu.toolUseId());
            tc.setName(tu.toolName());
            Map<String, Object> input = new HashMap<>();
            Map<String, Object> args = tu.arguments();
            if (args != null) {
                input.putAll(args);
            }
            tc.setInput(input);
            converted.add(tc);
        }
        return converted;
    }

    /** @deprecated Use {@link #getToolUses()} instead. */
    @Deprecated
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    public String getToolUseId() {
        if (toolUseId != null) return toolUseId;
        if (toolResults != null && !toolResults.isEmpty()) return toolResults.get(0).toolUseId();
        return null;
    }

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    public ToolResult getToolResult() {
        if (toolResult != null) return toolResult;
        if (toolResults != null && !toolResults.isEmpty()) {
            ToolResultBlock first = toolResults.get(0);
            return new ToolResult(!first.isError(), first.content());
        }
        return null;
    }

    /** @deprecated Use {@link #getToolResults()} instead. */
    @Deprecated
    public void setToolResult(ToolResult toolResult) { this.toolResult = toolResult; }

    @Override
    public String toString() {
        return role + ": " + (content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}

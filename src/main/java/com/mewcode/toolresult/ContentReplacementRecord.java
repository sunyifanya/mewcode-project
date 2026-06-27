package com.mewcode.toolresult;

/**
 * One transcript line: a single replacement decision made by
 * {@link ToolResultBudget#apply}, suitable for jsonl persistence so
 * replay can rebuild state on resume.
 */
public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {

    public static final String TOOL_RESULT = "tool-result";

    public static ContentReplacementRecord toolResult(String toolUseId, String replacement) {
        return new ContentReplacementRecord(TOOL_RESULT, toolUseId, replacement);
    }
}

package com.mewcode.tool;

import java.util.Map;

/**
 * Unified interface for all tools available to the LLM.
 * Each implementation handles one specific capability (read file, write file, etc.).
 */
public interface Tool {

    /**
     * @return unique tool name (e.g. "read_file", "grep"), used in Anthropic tool_use blocks
     */
    String getName();

    /**
     * @return one-line description of what the tool does, shown to the model
     */
    String getDescription();

    /**
     * @return JSON Schema describing the tool's parameters, as a Map suitable for
     *         Jackson serialization into Anthropic API's "input_schema" format
     */
    Map<String, Object> getParametersSchema();

    /**
     * Execute the tool with the given parameters.
     *
     * @param params parameter name → value map, keyed by the property names declared in getParametersSchema()
     * @return structured result (never null; failures are encoded as ToolResult.success=false)
     */
    ToolResult execute(Map<String, Object> params);

    /**
     * @return true if this tool has no side effects and is safe for concurrent execution.
     *         Default is false — tools that mutate state must explicitly opt in.
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * @return the permission category for this tool.
     *         Default is {@link ToolCategory#COMMAND} — tools must explicitly
     *         opt into READ or WRITE via override.
     */
    default ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    /**
     * @return true if this tool should be hidden from the model until explicitly
     *         discovered (e.g. via ToolSearchTool). MCP tools use this to avoid
     *         bloating the context window with tools the model hasn't asked for.
     *         Default is false — tools are visible by default.
     */
    default boolean shouldDefer() {
        return false;
    }
}

package com.mewcode.mcp;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts an MCP SDK tool ({@link McpSchema.Tool}) into MewCode's {@link Tool} interface.
 * The model invokes it by name like any built-in tool; the wrapper delegates execution
 * to the MCP client.
 *
 * <p>Naming convention: {@code mcp__<server>__<tool>}
 * Non-alphanumeric characters in either segment are replaced with {@code _}.
 */
class McpToolWrapper implements Tool {

    private final String serverName;
    private final McpSchema.Tool sdkTool;
    private final McpSyncClient client;

    McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpSyncClient client) {
        this.serverName = serverName;
        this.sdkTool = sdkTool;
        this.client = client;
    }

    // ── Tool interface ─────────────────────────────────────────────────

    @Override
    public String getName() {
        return "mcp__" + McpManager.sanitizeName(serverName)
                + "__" + McpManager.sanitizeName(sdkTool.name());
    }

    @Override
    public String getDescription() {
        String desc = sdkTool.description();
        return desc != null ? desc : "";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        var jsonSchema = sdkTool.inputSchema();
        if (jsonSchema != null) {
            if (jsonSchema.type() != null) schema.put("type", jsonSchema.type());
            if (jsonSchema.properties() != null) schema.put("properties", jsonSchema.properties());
            if (jsonSchema.required() != null) schema.put("required", jsonSchema.required());
        }
        if (schema.isEmpty()) {
            schema.put("type", "object");
            schema.put("properties", Map.of());
        }
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            var request = new McpSchema.CallToolRequest(
                    sdkTool.name(),
                    params != null ? params : Map.of());
            var result = client.callTool(request);
            String text = extractTextContent(result);
            boolean isError = result.isError() != null && result.isError();
            return isError
                    ? new ToolResult(false, text, "MCP_ERROR")
                    : new ToolResult(true, text);
        } catch (Exception e) {
            return new ToolResult(false,
                    "MCP tool call failed: " + e.getMessage(), "MCP_ERROR");
        }
    }

    @Override
    public ToolCategory category() {
        // Most MCP tools are read-only (query docs, search, list resources).
        // For tools that need write access, users can configure per-tool
        // rules in .mewcode/permissions.yaml.
        return ToolCategory.READ;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Concatenate all {@code text/plain} content blocks from a tool result.
     */
    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "(no output)";
        }
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}

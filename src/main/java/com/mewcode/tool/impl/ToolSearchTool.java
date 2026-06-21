package com.mewcode.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Search tool for discovering deferred (lazy-loaded) tools.
 */
public class ToolSearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String DESCRIPTION = """
            Search for tool schemas and load deferred tools. Use this to look up the exact \
            parameters (name, type, required/optional) of ANY tool before calling it -- \
            including built-in tools like read_file, write_file, grep, glob, edit_file, execute_command.

            Some tools are deferred (not loaded by default) to save context space. \
            When you select a deferred tool, it becomes loaded and available in subsequent requests.

            Query forms:
            - "select:ToolName,AnotherTool" -- fetch exact tools by name (works for all tools)
            - "keyword search" -- keyword search across all tools, returns up to max_results matches

            Always use this to confirm parameter names before calling a tool you haven't used recently. \
            Never guess parameter names.""";

    private final ToolRegistry registry;

    public ToolSearchTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "ToolSearch";
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean shouldDefer() {
        return false; // ToolSearch must always be visible
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Tool name or keyword. \"select:Name1,Name2\" for exact names, or a keyword to search across all tools (built-in + deferred). Always use this to confirm parameter schemas before calling a tool."
                        ),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum results to return (default: 5)",
                                "default", 5
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = stringArg(args, "query", "");
        if (query.isEmpty()) {
            return new ToolResult(false, "Error: query is required", "TOOLSEARCH_ERROR");
        }

        int maxResults = intArg(args, "max_results", 5);
        if (maxResults < 1) {
            maxResults = 5;
        }
        if (maxResults > 20) {
            maxResults = 20;
        }

        List<Map<String, Object>> schemas;

        if (query.startsWith("select:")) {
            List<String> names = Arrays.stream(query.substring("select:".length()).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            schemas = registry.findByNames(names);
        } else {
            schemas = registry.searchAll(query, maxResults);
        }

        if (schemas.isEmpty()) {
            List<Tool> all = registry.getAllTools();
            String nameList = all.stream()
                    .map(Tool::getName)
                    .collect(Collectors.joining(", "));
            return new ToolResult(true,
                    "No matching tools found for query \"" + query
                            + "\". Available tools: " + nameList);
        }

        // Mark found deferred tools as discovered
        for (var schema : schemas) {
            Object nameObj = schema.get("name");
            if (nameObj instanceof String n) {
                // Only mark deferred tools — built-in tools are always loaded
                Tool tool = registry.get(n);
                if (tool != null && tool.shouldDefer()) {
                    registry.markDiscovered(n);
                }
            }
        }

        String loadedNote = "";
        for (var schema : schemas) {
            Object nameObj = schema.get("name");
            if (nameObj instanceof String n) {
                Tool tool = registry.get(n);
                if (tool != null && tool.shouldDefer()) {
                    loadedNote = "\nDeferred tools are now loaded for subsequent requests.";
                    break;
                }
            }
        }

        String schemasJson;
        try {
            schemasJson = MAPPER.writeValueAsString(schemas);
        } catch (JsonProcessingException e) {
            return new ToolResult(false,
                    "Error serializing schemas: " + e.getMessage(), "TOOLSEARCH_ERROR");
        }

        return new ToolResult(true,
                "Found " + schemas.size() + " tool(s)."
                        + loadedNote + "\n\n" + schemasJson);
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}

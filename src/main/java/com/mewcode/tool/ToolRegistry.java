package com.mewcode.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all available tools.
 * Tools are registered at startup and looked up by name during execution.
 *
 * <p>Supports deferred tools ({@code shouldDefer() == true}) that are registered
 * but hidden from the model's tools array until explicitly discovered via
 * {@link #markDiscovered(String)}. This keeps the context window small while
 * still allowing tools from external sources (MCP) to be available on demand.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();

    public ToolRegistry() {}

    /**
     * Register a single tool. Replaces any existing tool with the same name.
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Register multiple tools at once.
     */
    public void registerAll(Tool... tools) {
        for (Tool t : tools) {
            register(t);
        }
    }

    /**
     * Look up a tool by name.
     *
     * @return the tool, or null if not found
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * @return the number of registered tools
     */
    public int size() {
        return tools.size();
    }

    /**
     * @return all tools that are marked read-only (isReadOnly() == true).
     */
    public List<Tool> getReadOnlyTools() {
        List<Tool> result = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (tool.isReadOnly()) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * @return all registered tools as a list.
     */
    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    // ── Deferred tool support ─────────────────────────────────────────

    /**
     * Mark a deferred tool as discovered, making it visible to the model.
     */
    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }

    /**
     * Check whether a deferred tool has been discovered.
     */
    public boolean isDiscovered(String name) {
        return discoveredTools.contains(name);
    }

    /**
     * @return names of tools that are deferred and not yet discovered.
     */
    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !isDiscovered(t.getName()))
                .map(Tool::getName)
                .toList();
    }

    /**
     * @return all tools that are marked as deferred.
     */
    public List<Tool> getDeferredTools() {
        return tools.values().stream()
                .filter(Tool::shouldDefer)
                .toList();
    }

    /**
     * Find tools by exact name match (case-insensitive) across ALL tools
     * (built-in + deferred). Used by ToolSearch for parameter lookup.
     *
     * @param names tool names to look up
     * @return matching tool schemas
     */
    public List<Map<String, Object>> findByNames(List<String> names) {
        Set<String> nameSet = new HashSet<>();
        for (String n : names) nameSet.add(n.toLowerCase());

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (nameSet.contains(tool.getName().toLowerCase())) {
                matches.add(toSchemaEntry(tool));
            }
        }
        return matches;
    }

    /**
     * Search ALL tools (built-in + deferred) by name or description (case-insensitive).
     *
     * @param query      search term
     * @param maxResults maximum number of results to return
     * @return matching tool schemas, up to maxResults
     */
    public List<Map<String, Object>> searchAll(String query, int maxResults) {
        String lower = query.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (tool.getName().toLowerCase().contains(lower)
                    || tool.getDescription().toLowerCase().contains(lower)) {
                matches.add(toSchemaEntry(tool));
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    /**
     * Search deferred tools by name or description (case-insensitive).
     *
     * @param query      search term
     * @param maxResults maximum number of results to return
     * @return matching tool schemas, up to maxResults
     */
    public List<Map<String, Object>> searchDeferred(String query, int maxResults) {
        String lower = query.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.getName().toLowerCase().contains(lower)
                    || tool.getDescription().toLowerCase().contains(lower)) {
                matches.add(toSchemaEntry(tool));
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    /**
     * Find deferred tools by exact name match (case-insensitive).
     *
     * @param names tool names to look up
     * @return matching tool schemas
     */
    public List<Map<String, Object>> findDeferredByNames(List<String> names) {
        Set<String> nameSet = new HashSet<>();
        for (String n : names) nameSet.add(n.toLowerCase());

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (nameSet.contains(tool.getName().toLowerCase())) {
                matches.add(toSchemaEntry(tool));
            }
        }
        return matches;
    }

    /**
     * Generate the "tools" array for Anthropic API requests.
     * Skips deferred tools that have not been discovered.
     */
    public List<Map<String, Object>> toApiFormat() {
        return toApiFormat(getAllTools());
    }

    /**
     * Generate the "tools" array for a specific subset of tools.
     * Skips deferred tools that have not been discovered.
     *
     * @param toolSubset the tools to include in the API request
     * @return list of tool definition maps ready for JSON serialization
     */
    public List<Map<String, Object>> toApiFormat(List<Tool> toolSubset) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : toolSubset) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.getName())) {
                continue;
            }
            result.add(toSchemaEntry(tool));
        }
        return result;
    }

    /**
     * Build a single tool schema entry from a Tool instance.
     */
    private static Map<String, Object> toSchemaEntry(Tool tool) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", tool.getName());
        entry.put("description", tool.getDescription());
        entry.put("input_schema", tool.getParametersSchema());
        return entry;
    }
}
package com.mewcode.tool;

import java.util.Map;

/**
 * Represents a single tool invocation extracted from an SSE stream.
 * The id is assigned by the API and used to match tool_result blocks.
 */
public class ToolCall {

    private String id;
    private String name;
    private Map<String, Object> input;

    public ToolCall() {}

    public ToolCall(String id, String name, Map<String, Object> input) {
        this.id = id;
        this.name = name;
        this.input = input;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input; }

    @Override
    public String toString() {
        return "ToolCall{id=" + id + ", name=" + name + ", input=" + input + "}";
    }
}
package com.mewcode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Holds parsed configuration from mewcode.yaml.
 */
public class AppConfig {

    @JsonProperty("protocol")
    private String protocol;

    @JsonProperty("model")
    private String model;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("thinking_budget")
    private int thinkingBudget = 16000;

    @JsonProperty("tool")
    private ToolConfig tool = new ToolConfig();

    @JsonProperty("permission")
    private PermissionConfigNode permission = new PermissionConfigNode();

    @JsonProperty("mcp")
    private McpConfigNode mcp;

    @JsonProperty("max_iterations")
    private int maxIterations = 25;

    @JsonProperty("stream_timeout_seconds")
    private int streamTimeoutSeconds = 300;

    @JsonProperty("max_session_age_days")
    private int maxSessionAgeDays = 30;

    @JsonProperty("extraction_interval")
    private int extractionInterval = 5;

    // -- getters --

    public String getProtocol() { return protocol; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public int getThinkingBudget() { return thinkingBudget; }
    public ToolConfig getTool() { return tool; }
    public PermissionConfigNode getPermission() { return permission; }
    public McpConfigNode getMcp() { return mcp; }
    public int getMaxIterations() { return maxIterations; }
    public int getStreamTimeoutSeconds() { return streamTimeoutSeconds; }
    public int getMaxSessionAgeDays() { return maxSessionAgeDays; }
    public int getExtractionInterval() { return extractionInterval; }

    // -- setters (used by Jackson) --

    public void setProtocol(String protocol) { this.protocol = protocol; }
    public void setModel(String model) { this.model = model; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setThinkingBudget(int thinkingBudget) { this.thinkingBudget = thinkingBudget; }
    public void setTool(ToolConfig tool) { this.tool = tool; }
    public void setPermission(PermissionConfigNode permission) { this.permission = permission; }
    public void setMcp(McpConfigNode mcp) { this.mcp = mcp; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public void setStreamTimeoutSeconds(int streamTimeoutSeconds) { this.streamTimeoutSeconds = streamTimeoutSeconds; }
    public void setMaxSessionAgeDays(int maxSessionAgeDays) { this.maxSessionAgeDays = maxSessionAgeDays; }
    public void setExtractionInterval(int extractionInterval) { this.extractionInterval = extractionInterval; }

    /**
     * Nested configuration node for {@code permission} section in mewcode.yaml.
     */
    public static class PermissionConfigNode {

        @JsonProperty("mode")
        private String mode = "default";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    /**
     * Nested configuration node for {@code mcp} section in mewcode.yaml.
     */
    public static class McpConfigNode {

        @JsonProperty("servers")
        private Map<String, McpServerConfig> servers;

        public Map<String, McpServerConfig> getServers() { return servers; }
        public void setServers(Map<String, McpServerConfig> servers) { this.servers = servers; }
    }
}
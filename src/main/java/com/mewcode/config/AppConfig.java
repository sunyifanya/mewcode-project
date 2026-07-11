package com.mewcode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
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

    @JsonProperty("subagent")
    private SubAgentConfig subagent = new SubAgentConfig();

    @JsonProperty("worktree")
    private WorktreeConfig worktree = new WorktreeConfig();

    @JsonProperty("team")
    private TeamConfig team = new TeamConfig();

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
    public SubAgentConfig getSubAgent() { return subagent; }
    public WorktreeConfig getWorktree() { return worktree; }
    public TeamConfig getTeam() { return team; }

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
    public void setSubAgent(SubAgentConfig subagent) { this.subagent = subagent; }
    public void setWorktree(WorktreeConfig worktree) { this.worktree = worktree; }
    public void setTeam(TeamConfig team) { this.team = team; }

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

    /**
     * Nested configuration node for {@code subagent} section in mewcode.yaml.
     */
    public static class SubAgentConfig {

        @JsonProperty("background")
        private SubAgentBackgroundConfig background = new SubAgentBackgroundConfig();

        @JsonProperty("max_turns")
        private int maxTurns = 25;

        public SubAgentBackgroundConfig getBackground() { return background; }
        public void setBackground(SubAgentBackgroundConfig background) { this.background = background; }

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

        /**
         * Nested config for background execution.
         */
        public static class SubAgentBackgroundConfig {

            @JsonProperty("enabled")
            private boolean enabled = true;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
    }

    /**
     * Nested configuration node for {@code worktree} section in mewcode.yaml.
     */
    public static class WorktreeConfig {

        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("symlink_dirs")
        private List<String> symlinkDirs = List.of();

        @JsonProperty("stale_cleanup_interval_seconds")
        private int staleCleanupIntervalSeconds = 3600;

        @JsonProperty("stale_cutoff_hours")
        private int staleCutoffHours = 24;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public List<String> getSymlinkDirs() { return symlinkDirs; }
        public void setSymlinkDirs(List<String> symlinkDirs) { this.symlinkDirs = symlinkDirs; }

        public int getStaleCleanupIntervalSeconds() { return staleCleanupIntervalSeconds; }
        public void setStaleCleanupIntervalSeconds(int staleCleanupIntervalSeconds) {
            this.staleCleanupIntervalSeconds = staleCleanupIntervalSeconds;
        }

        public int getStaleCutoffHours() { return staleCutoffHours; }
        public void setStaleCutoffHours(int staleCutoffHours) {
            this.staleCutoffHours = staleCutoffHours;
        }
    }

    /**
     * Nested configuration node for {@code team} section in mewcode.yaml.
     */
    public static class TeamConfig {

        @JsonProperty("coordinator")
        private CoordinatorConfig coordinator = new CoordinatorConfig();

        public CoordinatorConfig getCoordinator() { return coordinator; }
        public void setCoordinator(CoordinatorConfig coordinator) { this.coordinator = coordinator; }

        /**
         * Nested config for coordinator mode.
         */
        public static class CoordinatorConfig {

            @JsonProperty("enabled")
            private boolean enabled = false;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
    }
}
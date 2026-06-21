package com.mewcode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP server.
 *
 * <p>For stdio transport, set {@code command}, {@code args} (optional), and
 * {@code env} (optional). For Streamable HTTP transport, set {@code url} and
 * {@code headers} (optional).
 *
 * <p>{@code name} is not stored in this class — it comes from the map key in
 * {@code mcp.servers} in mewcode.yaml.
 */
public class McpServerConfig {

    // -- stdio transport fields --

    @JsonProperty("command")
    private String command;

    @JsonProperty("args")
    private List<String> args;

    @JsonProperty("env")
    private Map<String, String> env;

    // -- HTTP transport fields --

    @JsonProperty("url")
    private String url;

    @JsonProperty("headers")
    private Map<String, String> headers;

    // -- getters --

    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public Map<String, String> getEnv() { return env; }
    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return headers; }

    // -- setters (used by Jackson) --

    public void setCommand(String command) { this.command = command; }
    public void setArgs(List<String> args) { this.args = args; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public void setUrl(String url) { this.url = url; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
}
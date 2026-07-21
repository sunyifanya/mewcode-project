package com.mewcode.mcp;

import com.mewcode.config.McpServerConfig;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle of MCP server connections — connect, discover tools,
 * register into ToolRegistry, and graceful shutdown.
 *
 * <p>Each configured Server gets its own {@link McpSyncClient}. Failures in one
 * Server do not affect others.
 */
public class McpManager {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    /**
     * Result of connecting to all configured MCP servers.
     */
    public record ConnectResult(List<Tool> tools, List<String> serverNames, List<String> errors) {}

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();

    /**
     * @param configs server name → config map; entries with null value are silently dropped
     */
    public McpManager(Map<String, McpServerConfig> configs) {
        if (configs != null) {
            for (var entry : configs.entrySet()) {
                if (entry.getValue() != null) {
                    this.configs.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Connect to all configured servers, initialize, and list tools.
     * Each server is processed independently: one failure does not abort the rest.
     *
     * @return aggregated tools, connected server names, and error messages
     */
    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var serverNames = new ArrayList<String>();
        var errors = new ArrayList<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig cfg = entry.getValue();

            try {
                var client = createClient(cfg);
                client.initialize();
                clients.put(name, client);
                serverNames.add(name);

                var result = client.listTools();
                if (result != null && result.tools() != null) {
                    for (var sdkTool : result.tools()) {
                        tools.add(new McpToolWrapper(name, sdkTool, client));
                    }
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(serverNames), List.copyOf(errors));
    }

    /**
     * Connect all servers, register discovered tools into the toolRegistry,
     * and print connection status.
     *
     * @param toolRegistry the tool toolRegistry to register into
     * @param writer   the writer to use for output (e.g. from TerminalUI)
     */
    public void registerAllMcpTools(ToolRegistry toolRegistry, java.io.PrintWriter writer) {
        var connectResult = connectAll();
        for (var tool : connectResult.tools()) {
            toolRegistry.register(tool);
        }

        if (!connectResult.serverNames().isEmpty()) {
            writer.println("MCP: " + connectResult.serverNames().size() + " 个 Server 已连接，"
                    + connectResult.tools().size() + " 个工具已注册");
            writer.flush();
        }
        for (var err : connectResult.errors()) {
            writer.println("警告: " + err);
            writer.flush();
        }

    }

    /**
     * Gracefully close all client connections. Called at shutdown.
     * Each client is closed independently; exceptions during close are logged
     * but do not prevent other clients from being closed.
     */
    public void shutdown() {
        for (var entry : clients.entrySet()) {
            try {
                entry.getValue().closeGracefully();
            } catch (Exception e) {
                System.err.println("警告: 关闭 MCP Server '" + entry.getKey() + "' 时出错: " + e.getMessage());
            }
        }
        clients.clear();
    }

    // ── transport factory ──────────────────────────────────────────────

    private McpSyncClient createClient(McpServerConfig mcpServerConfig) {
        McpClientTransport transport;

        if (mcpServerConfig.getCommand() != null && !mcpServerConfig.getCommand().isBlank()) {
            // stdio transport
            var paramsBuilder = ServerParameters.builder(windowsSafe(mcpServerConfig.getCommand()));
            if (mcpServerConfig.getArgs() != null) {
                paramsBuilder.args(mcpServerConfig.getArgs());
            }
            if (mcpServerConfig.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : mcpServerConfig.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());

        } else if (mcpServerConfig.getUrl() != null && !mcpServerConfig.getUrl().isBlank()) {
            // Streamable HTTP transport
            var httpBuilder = HttpClientStreamableHttpTransport.builder(mcpServerConfig.getUrl());
            if (mcpServerConfig.getHeaders() != null && !mcpServerConfig.getHeaders().isEmpty()) {
                httpBuilder.httpRequestCustomizer((rb, method, uri, protocol, ctx) -> {
                    for (var e : mcpServerConfig.getHeaders().entrySet()) {
                        rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                    }
                });
            }
            transport = httpBuilder.build();

        } else {
            throw new IllegalArgumentException("Neither command nor url configured");
        }

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("mewcode", "MewCode", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    // ── static utilities (also used by McpToolWrapper) ─────────────────

    /**
     * On Windows, append {@code .cmd} to common Node.js commands so the OS
     * can resolve them via PATHEXT.
     */
    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return command;
        }
        String base = command.toLowerCase();
        if (WIN_CMD_SUFFIXED.contains(base)) {
            return command + ".cmd";
        }
        return command;
    }

    /**
     * Replace non-alphanumeric characters with underscores for safe tool naming.
     */
    static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    /**
     * Replace {@code ${VAR}} patterns with the corresponding environment variable.
     * Unresolved variables are left as-is.
     */
    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(matchResult -> {
            String env = System.getenv(matchResult.group(1));
            return env != null ? env : matchResult.group(0);
        });
    }
}

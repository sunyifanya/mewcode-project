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
     * Connect all servers, register discovered tools into the registry,
     * and print connection status.
     *
     * @param registry the tool registry to register into
     */
    public void registerAllMcpTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) {
            registry.register(t);
        }

        if (!result.serverNames().isEmpty()) {
            System.out.println("MCP: " + result.serverNames().size() + " 个 Server 已连接，"
                    + result.tools().size() + " 个工具已注册");
        }
        for (var err : result.errors()) {
            System.err.println("警告: " + err);
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

    private McpSyncClient createClient(McpServerConfig cfg) {
        McpClientTransport transport;

        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            // stdio transport
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());

        } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            // Streamable HTTP transport
            var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                httpBuilder.httpRequestCustomizer((rb, method, uri, protocol, ctx) -> {
                    for (var e : cfg.getHeaders().entrySet()) {
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
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return env != null ? env : m.group(0);
        });
    }
}

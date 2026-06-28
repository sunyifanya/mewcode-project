package com.mewcode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

/**
 * Loads and validates mewcode.yaml configuration.
 * 
 * <p>Configuration loading strategy:
 * <ol>
 *   <li>Try to load from the specified file path (default: ./mewcode.yaml)</li>
 *   <li>If not found, load mewcode.yaml from classpath resources</li>
 * </ol>
 */
public class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String CLASSPATH_CONFIG = "/mewcode.yaml";

    public static AppConfig load(String filePath) {
        File file = new File(filePath);

        if (!file.exists()) {
            System.err.println("错误: 配置文件不存在: " + filePath);
            System.err.println("请创建 mewcode.yaml 配置文件。参考项目根目录下的 mewcode.yaml 示例。");
            System.exit(1);
        }

        AppConfig config;
        try {
            config = YAML_MAPPER.readValue(file, AppConfig.class);
        } catch (FileNotFoundException e) {
            System.err.println("错误: 配置文件不存在: " + filePath);
            System.exit(1);
            return null;
        } catch (Exception e) {
            System.err.println("错误: 配置文件格式错误: " + e.getMessage());
            System.exit(1);
            return null;
        }

        if (config == null) {
            System.err.println("错误: 配置文件为空，请填写必要字段。");
            System.exit(1);
            return null;
        }

        // Validate required fields
        validate(config, filePath);

        // Normalize protocol to lowercase
        config.setProtocol(config.getProtocol().toLowerCase().trim());

        // Validate protocol value
        if (!config.getProtocol().equals("anthropic") && !config.getProtocol().equals("openai")) {
            System.err.println("错误: protocol 必须是 'anthropic' 或 'openai'，当前值: " + config.getProtocol());
            System.exit(1);
        }

        return config;
    }

    /**
     * Load configuration from classpath resource (/mewcode.yaml).
     * Used when no user configuration file exists.
     *
     * @return loaded AppConfig
     */
    public static AppConfig loadFromClasspath() {
        InputStream resourceStream = ConfigLoader.class.getResourceAsStream(CLASSPATH_CONFIG);
        
        if (resourceStream == null) {
            System.err.println("错误: 未找到内置配置文件 " + CLASSPATH_CONFIG);
            System.err.println("请创建 mewcode.yaml 配置文件。");
            System.exit(1);
            return null;
        }

        AppConfig config;
        try {
            config = YAML_MAPPER.readValue(resourceStream, AppConfig.class);
        } catch (Exception e) {
            System.err.println("错误: 内置配置文件格式错误: " + e.getMessage());
            System.exit(1);
            return null;
        }

        if (config == null) {
            System.err.println("错误: 内置配置文件为空。");
            System.exit(1);
            return null;
        }

        // Validate required fields (use "classpath:mewcode.yaml" as path for error messages)
        validate(config, "classpath:mewcode.yaml");

        // Normalize protocol to lowercase
        config.setProtocol(config.getProtocol().toLowerCase().trim());

        // Validate protocol value
        if (!config.getProtocol().equals("anthropic") && !config.getProtocol().equals("openai")) {
            System.err.println("错误: protocol 必须是 'anthropic' 或 'openai'，当前值: " + config.getProtocol());
            System.exit(1);
        }

        return config;
    }

    private static void validate(AppConfig config, String filePath) {
        if (isBlank(config.getProtocol())) {
            System.err.println("错误: 配置文件缺少必填字段 'protocol'");
            System.exit(1);
        }
        if (isBlank(config.getModel())) {
            System.err.println("错误: 配置文件缺少必填字段 'model'");
            System.exit(1);
        }
        if (isBlank(config.getBaseUrl())) {
            System.err.println("错误: 配置文件缺少必填字段 'base_url'");
            System.exit(1);
        }
        if (isBlank(config.getApiKey())) {
            System.err.println("错误: 配置文件缺少必填字段 'api_key'");
            System.exit(1);
        }
        if (config.getThinkingBudget() < 0) {
            config.setThinkingBudget(0);
        }

        // Validate / default-fill tool config
        if (config.getTool() == null) {
            config.setTool(new ToolConfig());
        }
        if (config.getTool().getTimeoutSeconds() <= 0) {
            config.getTool().setTimeoutSeconds(30);
        }
        if (isBlank(config.getTool().getWorkingDirectory())) {
            config.getTool().setWorkingDirectory(".");
        }

        // Agent loop defaults
        if (config.getMaxIterations() <= 0) {
            config.setMaxIterations(25);
        }
        if (config.getStreamTimeoutSeconds() <= 0) {
            config.setStreamTimeoutSeconds(300);
        }
        if (config.getMaxSessionAgeDays() <= 0) {
            config.setMaxSessionAgeDays(30);
        }
        if (config.getExtractionInterval() <= 0) {
            config.setExtractionInterval(5);
        }

        // Permission defaults
        if (config.getPermission() == null) {
            config.setPermission(new AppConfig.PermissionConfigNode());
        }
        String mode = config.getPermission().getMode();
        if (mode == null || mode.isBlank()) {
            config.getPermission().setMode("default");
        } else {
            String lower = mode.trim().toLowerCase();
            switch (lower) {
                case "default", "accept-edits", "accept_edits", "plan", "bypass", "yolo" -> {
                    // Normalize accept_edits to accept-edits
                    if ("accept_edits".equals(lower)) {
                        config.getPermission().setMode("accept-edits");
                    } else {
                        config.getPermission().setMode(lower);
                    }
                }
                // Backward compatibility
                case "strict" -> {
                    System.err.println("警告: permission.mode 'strict' 已废弃，回退为 'default'");
                    config.getPermission().setMode("default");
                }
                case "permissive" -> {
                    System.err.println("警告: permission.mode 'permissive' 已废弃，回退为 'bypass'");
                    config.getPermission().setMode("bypass");
                }
                default -> {
                    System.err.println("警告: permission.mode 值无效 '" + mode + "'，回退为 'default'");
                    config.getPermission().setMode("default");
                }
            }
        }

        // MCP server config validation
        validateMcp(config);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Validate MCP server configurations.
     * Skips entries with null key, null value, or missing command/url.
     * Prints warnings for skipped entries. Backward-compatible: no mcp section = no-op.
     */
    private static void validateMcp(AppConfig config) {
        AppConfig.McpConfigNode mcp = config.getMcp();
        if (mcp == null) return;

        Map<String, McpServerConfig> servers = mcp.getServers();
        if (servers == null || servers.isEmpty()) return;

        var it = servers.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            String name = entry.getKey();
            McpServerConfig cfg = entry.getValue();

            // Remove entries with null/empty key
            if (isBlank(name)) {
                System.err.println("警告: MCP Server 配置缺少名称（key 为空），已跳过");
                it.remove();
                continue;
            }

            // Remove entries with null value
            if (cfg == null) {
                System.err.println("警告: MCP Server '" + name + "' 配置为空，已跳过");
                it.remove();
                continue;
            }

            // Validate: need at least command or url
            boolean hasCommand = !isBlank(cfg.getCommand());
            boolean hasUrl = !isBlank(cfg.getUrl());
            if (!hasCommand && !hasUrl) {
                System.err.println("警告: MCP Server '" + name + "' 缺少 command 或 url，已跳过");
                it.remove();
            }
        }
    }
}
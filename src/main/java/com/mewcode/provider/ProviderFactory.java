package com.mewcode.provider;

import com.mewcode.config.AppConfig;
import com.mewcode.tool.ToolRegistry;

/**
 * Creates the correct LLMProvider implementation based on config protocol.
 */
public class ProviderFactory {

    /**
     * @param config validated configuration
     * @param toolRegistry the tool registry (may be null if no tools are registered)
     * @return provider matching config.protocol
     * @throws IllegalArgumentException if protocol is unrecognized
     */
    public static LLMProvider create(AppConfig config, ToolRegistry toolRegistry) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicProvider(config, toolRegistry);
            case "openai" -> new OpenAIProvider(config);
            default -> throw new IllegalArgumentException(
                    "不支持的 protocol: " + config.getProtocol() + "（支持 anthropic / openai）");
        };
    }
}

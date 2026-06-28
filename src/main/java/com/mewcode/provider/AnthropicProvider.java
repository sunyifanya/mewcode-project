package com.mewcode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.config.AppConfig;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Messages API provider with SSE streaming, extended thinking, and tool-use support.
 */
public class AnthropicProvider implements LLMProvider {

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper jsonMapper;
    private final ToolRegistry toolRegistry;

    // ---- per-stream state ----
    private String currentBlockType;
    private String currentToolUseId;
    private String currentToolUseName;
    private StringBuilder toolInputJsonBuffer;
    private String lastStopReason;
    private int lastInputTokens;
    private int lastOutputTokens;
    private long lastCacheCreationTokens;
    private long lastCacheReadTokens;

    public AnthropicProvider(AppConfig config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.jsonMapper = new ObjectMapper();
    }

    @Override
    public void streamChat(List<Message> messages, StreamCallback callback) {
        streamChat(messages, callback, toolRegistry != null ? toolRegistry.getAllTools() : List.of());
    }

    /**
     * Send a streaming chat request with a specific tool subset (e.g. read-only tools for Plan Mode).
     */
    @Override
    public void streamChat(List<Message> messages, StreamCallback callback, List<Tool> toolSubset) {
        streamWithRetry(messages, callback, 0, toolSubset);
    }

    private void streamWithRetry(List<Message> messages, StreamCallback callback, int attempt) {
        streamWithRetry(messages, callback, attempt, toolRegistry != null ? toolRegistry.getAllTools() : List.of());
    }

    private void streamWithRetry(List<Message> messages, StreamCallback callback, int attempt, List<Tool> toolSubset) {
        try {
            String requestBody = buildRequestBody(messages, toolSubset);
            String url = stripTrailingSlash(config.getBaseUrl()) + "/messages";

            Request request = new Request.Builder()
                    .url(url)
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", "prompt-caching-2024-07-31")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    String errorBody = response.body() != null ? response.body().string() : "";
                    if (shouldRetry(code) && attempt < 3) {
                        long delayMs = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s
                        callback.onChunk("[重试 " + (attempt + 1) + "/3，等待 " + (delayMs / 1000) + "s...]\n", ChunkType.ERROR);
                        Thread.sleep(delayMs);
                        streamWithRetry(messages, callback, attempt + 1, toolSubset);
                        return;
                    }
                    callback.onChunk("API 错误 (" + code + "): " + errorBody, ChunkType.ERROR);
                    callback.onComplete("error_api");
                    return;
                }

                parseSSEStream(response, callback);
            }
        } catch (IOException e) {
            if (attempt < 3) {
                long delayMs = (long) Math.pow(2, attempt) * 1000;
                callback.onChunk("[网络错误，重试 " + (attempt + 1) + "/3...]\n", ChunkType.ERROR);
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                streamWithRetry(messages, callback, attempt + 1, toolSubset);
            } else {
                callback.onChunk("网络错误（已重试 3 次）: " + e.getMessage(), ChunkType.ERROR);
                callback.onComplete("error_network");
            }
        } catch (Exception e) {
            callback.onChunk("请求构建错误: " + e.getMessage(), ChunkType.ERROR);
            callback.onComplete("error_request");
        }
    }

    private String buildRequestBody(List<Message> messages, List<com.mewcode.tool.Tool> toolSubset) throws Exception {
        var root = jsonMapper.createObjectNode();
        root.put("model", config.getModel());
        root.put("max_tokens", 4096);
        root.put("stream", true);

        // Extended thinking — only for models that support it
        int thinkingBudget = config.getThinkingBudget();
        if (thinkingBudget > 0) {
            var thinkingNode = jsonMapper.createObjectNode();
            thinkingNode.put("type", "enabled");
            thinkingNode.put("budget_tokens", thinkingBudget);
            root.set("thinking", thinkingNode);
        }

        // Tools array — use the provided subset
        if (toolSubset != null && !toolSubset.isEmpty()) {
            root.set("tools", jsonMapper.valueToTree(toolRegistry.toApiFormat(toolSubset)));
        }

        // Separate system message from conversation messages
        String systemPrompt = null;
        var messagesArray = jsonMapper.createArrayNode();

        for (Message msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemPrompt = msg.getContent();
            } else {
                var msgNode = jsonMapper.createObjectNode();
                msgNode.put("role", msg.getRole());
                serializeContent(msg, msgNode);
                messagesArray.add(msgNode);
            }
        }

        if (systemPrompt != null) {
            // Use array format with cache_control for Prompt Caching
            var systemArray = jsonMapper.createArrayNode();
            var textBlock = jsonMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", systemPrompt);
            
            // Add cache_control to enable caching for the system prompt
            var cacheControl = jsonMapper.createObjectNode();
            cacheControl.put("type", "ephemeral");
            textBlock.set("cache_control", cacheControl);
            
            systemArray.add(textBlock);
            root.set("system", systemArray);
        }

        root.set("messages", messagesArray);
        return jsonMapper.writeValueAsString(root);
    }

    /**
     * Serialize message content: plain string for normal messages,
     * content array for tool_use and tool_result messages.
     */
    private void serializeContent(Message msg, ObjectNode msgNode) {
        List<ToolCall> toolCalls = msg.getToolCalls();
        List<ToolResultBlock> toolResults = msg.getToolResults();

        if (toolCalls != null && !toolCalls.isEmpty()) {
            // Assistant message with tool_use blocks
            var contentArray = jsonMapper.createArrayNode();

            // Text block first (if non-empty)
            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                var textBlock = jsonMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", msg.getContent());
                contentArray.add(textBlock);
            }

            // tool_use blocks
            for (ToolCall tc : toolCalls) {
                var toolUseBlock = jsonMapper.createObjectNode();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", tc.getId());
                toolUseBlock.put("name", tc.getName());
                toolUseBlock.set("input", jsonMapper.valueToTree(tc.getInput()));
                contentArray.add(toolUseBlock);
            }

            msgNode.set("content", contentArray);
        } else if (toolResults != null && !toolResults.isEmpty()) {
            // User message with tool_result block(s)
            var contentArray = jsonMapper.createArrayNode();
            for (ToolResultBlock tr : toolResults) {
                var resultBlock = jsonMapper.createObjectNode();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", tr.toolUseId());
                resultBlock.put("content", tr.content());
                if (tr.isError()) {
                    resultBlock.put("is_error", true);
                }
                contentArray.add(resultBlock);
            }
            msgNode.set("content", contentArray);
        } else {
            // Plain text message — content is a string
            msgNode.put("content", msg.getContent());
        }
    }

    private void parseSSEStream(Response response, StreamCallback callback) throws IOException {
        // Reset per-stream state
        currentBlockType = null;
        currentToolUseId = null;
        currentToolUseName = null;
        toolInputJsonBuffer = new StringBuilder();
        lastStopReason = null;
        lastInputTokens = 0;
        lastOutputTokens = 0;
        lastCacheCreationTokens = 0;
        lastCacheReadTokens = 0;

        assert response.body() != null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()));

        String line;
        String currentEvent = null;
        StringBuilder dataBuffer = new StringBuilder();

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
                dataBuffer.append(line.substring(6));
            } else if (line.isEmpty() && !dataBuffer.isEmpty()) {
                // End of an SSE event — process it
                processSSEEvent(currentEvent, dataBuffer.toString(), callback);
                dataBuffer.setLength(0);
                currentEvent = null;
            }
        }
        // Handle last event if no trailing newline
        if (!dataBuffer.isEmpty()) {
            processSSEEvent(currentEvent, dataBuffer.toString(), callback);
        }

        callback.onUsage(lastInputTokens, lastOutputTokens,
                lastCacheCreationTokens, lastCacheReadTokens);
        callback.onComplete(lastStopReason);
    }

    private void processSSEEvent(String eventType, String data, StreamCallback callback) {
        try {
            JsonNode root = jsonMapper.readTree(data);

            switch (eventType != null ? eventType : "") {
                case "content_block_start" -> {
                    JsonNode block = root.get("content_block");
                    if (block != null) {
                        String type = block.has("type") ? block.get("type").asText() : null;
                        currentBlockType = type;
                        if ("tool_use".equals(type)) {
                            currentToolUseId = block.has("id") ? block.get("id").asText() : null;
                            currentToolUseName = block.has("name") ? block.get("name").asText() : null;
                            toolInputJsonBuffer.setLength(0);
                        }
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = root.get("delta");
                    if (delta != null) {
                        JsonNode typeNode = delta.get("type");
                        String deltaType = typeNode != null ? typeNode.asText() : null;

                        switch (deltaType) {
                            case "input_json_delta" -> {
                                // Accumulate tool_use JSON fragments
                                JsonNode partial = delta.get("partial_json");
                                if (partial != null) {
                                    toolInputJsonBuffer.append(partial.asText());
                                }
                            }
                            case "thinking_delta" -> {
                                JsonNode thinking = delta.get("thinking");
                                if (thinking != null && !thinking.asText().isEmpty()) {
                                    callback.onChunk(thinking.asText(), ChunkType.THINKING);
                                }
                            }
                            case "text_delta" -> {
                                JsonNode text = delta.get("text");
                                if (text != null && !text.asText().isEmpty()) {
                                    callback.onChunk(text.asText(), ChunkType.TEXT);
                                }
                            }
                            case null, default -> {
                                // Fallback: try legacy fields (for older API versions)
                                JsonNode thinkingDelta = delta.get("thinking");
                                JsonNode textDelta = delta.get("text");
                                if (thinkingDelta != null && !thinkingDelta.asText().isEmpty()) {
                                    callback.onChunk(thinkingDelta.asText(), ChunkType.THINKING);
                                } else if (textDelta != null && !textDelta.asText().isEmpty()) {
                                    callback.onChunk(textDelta.asText(), ChunkType.TEXT);
                                }
                            }
                        }
                    }
                }
                case "content_block_stop" -> {
                    if ("tool_use".equals(currentBlockType) && !toolInputJsonBuffer.isEmpty()) {
                        // Assemble complete tool call JSON and deliver
                        String fullJson = toolInputJsonBuffer.toString();
                        // Wrap into a structure with id + name + input for the consumer
                        Map<String, Object> toolCallMap = new LinkedHashMap<>();
                        toolCallMap.put("id", currentToolUseId);
                        toolCallMap.put("name", currentToolUseName);
                        toolCallMap.put("input", jsonMapper.readValue(fullJson, Map.class));
                        String serialized = jsonMapper.writeValueAsString(toolCallMap);
                        callback.onChunk(serialized, ChunkType.TOOL_CALL);

                        // Reset
                        currentBlockType = null;
                        currentToolUseId = null;
                        currentToolUseName = null;
                        toolInputJsonBuffer.setLength(0);
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = root.get("delta");
                    if (delta != null) {
                        if (delta.has("stop_reason")) {
                            lastStopReason = delta.get("stop_reason").asText();
                        }
                        JsonNode usage = delta.get("usage");
                        if (usage != null && usage.has("output_tokens")) {
                            lastOutputTokens = usage.get("output_tokens").asInt();
                        }
                    }
                }
                case "message_stop" -> {
                    // Fallback: older API versions put stop_reason in message_stop.message
                    JsonNode message = root.get("message");
                    if (message != null && message.has("stop_reason")) {
                        lastStopReason = message.get("stop_reason").asText();
                    }
                }
                case "error" -> {
                    JsonNode error = root.get("error");
                    String msg = error != null && error.has("message")
                            ? error.get("message").asText()
                            : data;
                    callback.onChunk(msg, ChunkType.ERROR);
                }
                case "ping" -> {
                    // ignore keepalive pings
                }
                case "message_start" -> {
                    JsonNode message = root.get("message");
                    if (message != null) {
                        JsonNode usage = message.get("usage");
                        if (usage != null) {
                            lastInputTokens = usage.has("input_tokens")
                                    ? usage.get("input_tokens").asInt() : 0;
                            lastOutputTokens = usage.has("output_tokens")
                                    ? usage.get("output_tokens").asInt() : 0;
                            lastCacheCreationTokens = usage.has("cache_creation_input_tokens")
                                    ? usage.get("cache_creation_input_tokens").asLong() : 0;
                            lastCacheReadTokens = usage.has("cache_read_input_tokens")
                                    ? usage.get("cache_read_input_tokens").asLong() : 0;
                        }
                    }
                }
                default -> {
                    // unknown event type — no action needed
                }
            }
        } catch (Exception e) {
            // Skip malformed SSE lines silently — they are rare and usually recoverable
        }
    }

    private boolean shouldRetry(int httpCode) {
        return httpCode >= 500 || httpCode == 429;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }
}

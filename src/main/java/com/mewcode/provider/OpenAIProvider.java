package com.mewcode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.AppConfig;
import com.mewcode.conversation.Message;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Chat Completions API provider with SSE streaming.
 */
public class OpenAIProvider implements LLMProvider {

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper jsonMapper;

    public OpenAIProvider(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.jsonMapper = new ObjectMapper();
    }

    @Override
    public void streamChat(List<Message> messages, StreamCallback callback) {
        streamWithRetry(messages, callback, 0);
    }

    private void streamWithRetry(List<Message> messages, StreamCallback callback, int attempt) {
        try {
            String requestBody = buildRequestBody(messages);
            String url = stripTrailingSlash(config.getBaseUrl()) + "/chat/completions";

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    String errorBody = response.body() != null ? response.body().string() : "";
                    if (shouldRetry(code) && attempt < 3) {
                        long delayMs = (long) Math.pow(2, attempt) * 1000;
                        callback.onChunk("[重试 " + (attempt + 1) + "/3，等待 " + (delayMs / 1000) + "s...]\n", ChunkType.ERROR);
                        Thread.sleep(delayMs);
                        streamWithRetry(messages, callback, attempt + 1);
                        return;
                    }
                    callback.onChunk("API 错误 (" + code + "): " + errorBody, ChunkType.ERROR);
                    callback.onComplete("stop");
                    return;
                }

                parseSSEStream(response, callback);
            }
        } catch (IOException e) {
            if (attempt < 3) {
                long delayMs = (long) Math.pow(2, attempt) * 1000;
                callback.onChunk("[网络错误，重试 " + (attempt + 1) + "/3...]\n", ChunkType.ERROR);
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                streamWithRetry(messages, callback, attempt + 1);
            } else {
                callback.onChunk("网络错误（已重试 3 次）: " + e.getMessage(), ChunkType.ERROR);
                callback.onComplete("stop");
            }
        } catch (Exception e) {
            callback.onChunk("请求构建错误: " + e.getMessage(), ChunkType.ERROR);
            callback.onComplete("stop");
        }
    }

    private String buildRequestBody(List<Message> messages) throws Exception {
        var root = jsonMapper.createObjectNode();
        root.put("model", config.getModel());
        root.put("stream", true);

        var messagesArray = jsonMapper.createArrayNode();
        for (Message msg : messages) {
            var msgNode = jsonMapper.createObjectNode();
            msgNode.put("role", msg.getRole());
            msgNode.put("content", msg.getContent());
            messagesArray.add(msgNode);
        }
        root.set("messages", messagesArray);

        return jsonMapper.writeValueAsString(root);
    }

    private void parseSSEStream(Response response, StreamCallback callback) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()));
        String line;

        while ((line = reader.readLine()) != null) {
            // OpenAI SSE: lines starting with "data: " then JSON, or "data: [DONE]"
            if (line.startsWith("data: ")) {
                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    callback.onComplete("stop");
                    return;
                }

                try {
                    JsonNode root = jsonMapper.readTree(data);
                    JsonNode choices = root.get("choices");
                    if (choices != null && choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).get("delta");
                        if (delta != null) {
                            JsonNode content = delta.get("content");
                            if (content != null && !content.asText().isEmpty()) {
                                callback.onChunk(content.asText(), ChunkType.TEXT);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed SSE lines
                }
            }
        }
        // If we exit the loop without [DONE], treat as complete
        callback.onComplete("stop");
    }

    private boolean shouldRetry(int httpCode) {
        return httpCode >= 500 || httpCode == 429;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}

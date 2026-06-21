package com.mewcode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.provider.ChunkType;
import com.mewcode.provider.StreamCallback;
import com.mewcode.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Dual-path stream collector: pushes events to the UI queue in real time while
 * accumulating the full response (text, tool calls, stop reason) for the Agent
 * Loop to make continuation decisions.
 */
public class StreamingCollector implements StreamCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BlockingQueue<AgentEvent> eventQueue;
    private final CountDownLatch doneLatch = new CountDownLatch(1);

    private final StringBuilder fullText = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private String stopReason;
    private Throwable error;

    // ---- per-request token usage ----
    private int inputTokens;
    private int outputTokens;
    private long cacheCreationTokens;
    private long cacheReadTokens;

    public StreamingCollector(BlockingQueue<AgentEvent> eventQueue) {
        this.eventQueue = eventQueue;
    }

    // ---- StreamCallback implementation ----

    @Override
    public void onChunk(String text, ChunkType type) {
        if (type == ChunkType.TOOL_CALL) {
            try {
                ToolCall tc = JSON.readValue(text, ToolCall.class);
                toolCalls.add(tc);
                // Push TOOL_CALL_START to UI
                offerEvent(AgentEvent.of(AgentEventType.TOOL_CALL_START)
                        .toolName(tc.getName())
                        .callId(tc.getId())
                        .build());
            } catch (Exception e) {
                // Malformed tool call — shouldn't happen with AnthropicProvider
            }
        } else {
            // TEXT, THINKING, ERROR — push to UI and accumulate text
            offerEvent(AgentEvent.of(AgentEventType.TEXT_DELTA)
                    .text(text)
                    .chunkType(type)
                    .build());
            if (type == ChunkType.TEXT) {
                fullText.append(text);
            }
        }
    }

    @Override
    public void onComplete(String stopReason) {
        this.stopReason = stopReason;
        doneLatch.countDown();
    }

    @Override
    public void onUsage(int inputTokens, int outputTokens,
                        long cacheCreationTokens, long cacheReadTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
    }

    @Override
    public void onError(Throwable t) {
        this.error = t;
        offerEvent(AgentEvent.of(AgentEventType.ERROR)
                .message(t.getMessage())
                .build());
        doneLatch.countDown();
    }

    // ---- blocking wait for the Agent Loop ----

    /**
     * Block until the stream completes or times out.
     *
     * @param timeoutMs max wait time in milliseconds
     * @return true if completed, false if timed out
     */
    public boolean awaitCompletion(long timeoutMs) {
        try {
            return doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- getters for the Agent Loop ----

    public String getFullText() {
        return fullText.toString();
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls; // caller must not mutate
    }

    public String getStopReason() {
        return stopReason;
    }

    public Throwable getError() {
        return error;
    }

    public int getInputTokens() { return inputTokens; }

    public int getOutputTokens() { return outputTokens; }

    public long getCacheCreationTokens() { return cacheCreationTokens; }

    public long getCacheReadTokens() { return cacheReadTokens; }

    // ---- internal ----

    private void offerEvent(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

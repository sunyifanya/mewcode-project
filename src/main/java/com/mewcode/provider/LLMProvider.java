package com.mewcode.provider;

import com.mewcode.conversation.Message;
import com.mewcode.tool.Tool;

import java.util.List;

/**
 * Unified interface for LLM API backends.
 * Each implementation handles one provider's streaming protocol.
 */
public interface LLMProvider {

    /**
     * Send a streaming chat request with all registered tools.
     *
     * @param messages full conversation history including the latest user message
     * @param callback receives each chunk as it arrives
     */
    void streamChat(List<Message> messages, StreamCallback callback);

    /**
     * Send a streaming chat request with a specific tool subset (e.g. read-only tools for Plan Mode).
     *
     * @param messages   full conversation history
     * @param callback   receives each chunk as it arrives
     * @param toolSubset tools to expose to the model for this request
     */
    default void streamChat(List<Message> messages, StreamCallback callback, List<Tool> toolSubset) {
        streamChat(messages, callback);
    }

    /**
     * Send a streaming chat request with a specific tool subset and an optional model
     * override. The override may be a shorthand alias (e.g. "sonnet", "opus", "haiku")
     * or a full model ID. The default implementation ignores it and delegates to
     * {@link #streamChat(List, StreamCallback, List)}.
     */
    default void streamChat(List<Message> messages, StreamCallback callback,
                            List<Tool> toolSubset, String modelOverride) {
        streamChat(messages, callback, toolSubset);
    }

    /**
     * @return short provider identifier (e.g. "anthropic", "openai")
     */
    String getProviderName();
}

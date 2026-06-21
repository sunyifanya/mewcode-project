package com.mewcode.memory;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.provider.ChunkType;
import com.mewcode.provider.LLMProvider;
import com.mewcode.provider.StreamCallback;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronously extracts memories from conversations using an LLM call.
 *
 * <p>Runs on its own single-thread executor. Each extraction:
 * <ol>
 *   <li>Builds a conversation snapshot</li>
 *   <li>Calls the LLM with an extraction prompt (no tools)</li>
 *   <li>Parses the output into typed sections</li>
 *   <li>Upserts entries into the MemoryManager (dedup by content overlap)</li>
 * </ol>
 */
public class MemoryExtractor {

    private static final int EXTRACT_TIMEOUT_SECONDS = 30;

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Create a MemoryExtractor with a dedicated single-thread executor.
     */
    public MemoryExtractor() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-extractor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit an extraction task (fire-and-forget). Does not block the caller.
     *
     * @param provider  the LLM provider to use for extraction
     * @param conv      the current conversation (snapshot is taken immediately)
     * @param memoryMgr where to store extracted memories
     */
    public void extractAsync(LLMProvider provider, ConversationManager conv, MemoryManager memoryMgr) {
        if (!running.compareAndSet(false, true)) {
            return; // already running an extraction
        }

        // Take a snapshot of the conversation IMMEDIATELY (on the calling thread)
        List<Message> snapshot = new ArrayList<>(conv.getMessages());

        executor.submit(() -> {
            try {
                extractSync(provider, snapshot, memoryMgr);
            } catch (Exception e) {
                System.err.println("[记忆提取] 异常: " + e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Synchronous extraction — runs on the executor thread.
     */
    void extractSync(LLMProvider provider, List<Message> snapshot, MemoryManager memoryMgr) {
        if (snapshot.size() < 4) {
            return; // not enough content to extract from
        }

        // Build the conversation text for the extraction prompt
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : snapshot) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content != null && !content.isBlank()) {
                conversationText.append('[').append(role).append("]: ")
                        .append(content).append('\n');
            }
        }

        // Build extraction conversation (no system prompt, no tools)
        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(buildExtractionPrompt(conversationText.toString()));

        // Collect the response
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        String[] errorHolder = {null};

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onChunk(String text, ChunkType type) {
                if (type == ChunkType.TEXT) {
                    result.append(text);
                }
            }

            @Override
            public void onComplete(String stopReason) {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                errorHolder[0] = t.getMessage();
                done.countDown();
            }
        };

        // Call LLM — empty tool list (no tools for extraction)
        provider.streamChat(extractConv.getMessages(), callback, List.of());

        try {
            boolean completed = done.await(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                System.err.println("[记忆提取] LLM 调用超时");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (errorHolder[0] != null) {
            System.err.println("[记忆提取] LLM 错误: " + errorHolder[0]);
            return;
        }

        String output = result.toString().trim();
        if (output.isEmpty()) {
            return;
        }

        // Parse the output into typed sections
        Map<String, String> byType = parseTypedSections(output);
        if (byType.isEmpty()) {
            return;
        }

        // Convert to MemoryEntry objects
        List<MemoryEntry> newEntries = new ArrayList<>();
        for (Map.Entry<String, String> section : byType.entrySet()) {
            String type = section.getKey();
            String body = section.getValue().trim();
            if (body.isEmpty()) continue;

            // Validate type
            if (!MemoryManager.USER_TYPES.contains(type)
                    && !MemoryManager.PROJECT_TYPES.contains(type)) {
                continue;
            }

            // Split into individual items (each line starting with "- " or "* ")
            String[] items = body.split("\n(?=[-*] )");
            for (String item : items) {
                String trimmed = item.trim();
                // Strip bullet prefix
                if (trimmed.startsWith("- ")) {
                    trimmed = trimmed.substring(2).trim();
                } else if (trimmed.startsWith("* ")) {
                    trimmed = trimmed.substring(2).trim();
                }
                if (trimmed.isEmpty()) continue;

                String slug = MemoryEntry.slugFromText(trimmed);
                newEntries.add(new MemoryEntry(slug, trimmed, type, trimmed));
            }
        }

        if (!newEntries.isEmpty()) {
            int saved = memoryMgr.upsertEntries(newEntries);
            if (saved > 0) {
                System.out.println("[记忆] 提取了 " + saved + " 条新记忆");
            }
        }
    }

    /**
     * Build the extraction prompt instructing the LLM to extract key facts.
     */
    private static String buildExtractionPrompt(String conversationText) {
        return """
                从以下对话中提取值得跨会话记住的关键信息。
                将每条信息分类到以下四种类型之一（类型决定了信息的存储作用域）：
                - `user`（用户级作用域）：用户的偏好、角色或背景，适用于所有项目
                - `feedback`（用户级作用域）：用户给出的纠正意见或认可的做法
                - `project`（项目级作用域）：与当前项目相关的事实（技术栈、约定、截止日期等）
                - `reference`（项目级作用域）：与该项目关联的外部资源（文档、看板等）

                严格按照以下标题格式输出——如果某个类别没有值得保存的内容，则跳过该类别：

                ### user
                - 条目1
                - 条目2

                ### feedback
                - 条目3

                ### project
                - 条目4

                ### reference
                - 条目5

                不要输出任何其他内容（不要前言，不要解释）。如果没有任何值得记住的内容，只输出四个空标题即可。
                不要重复已存在于现有记忆中的信息。保持简洁——每条一行。

                对话内容：
                """ + conversationText;
    }

    /**
     * Parse the LLM output into a {@code type → body} map.
     * Groups lines under {@code ### <type>} headers.
     */
    static Map<String, String> parseTypedSections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String currentType = null;
        StringBuilder buf = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (currentType != null) {
                    String body = buf.toString().trim();
                    if (!body.isEmpty()) {
                        out.merge(currentType, body, (a, b) -> a + "\n" + b);
                    }
                }
                currentType = trimmed.substring(4).trim().toLowerCase(Locale.ROOT);
                buf.setLength(0);
            } else if (currentType != null) {
                buf.append(line).append('\n');
            }
        }

        if (currentType != null) {
            String body = buf.toString().trim();
            if (!body.isEmpty()) {
                out.merge(currentType, body, (a, b) -> a + "\n" + b);
            }
        }

        return out;
    }

    /**
     * Shutdown the executor gracefully.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

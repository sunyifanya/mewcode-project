package com.mewcode.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.provider.LLMProvider;
import com.mewcode.provider.StreamCallback;
import com.mewcode.provider.ChunkType;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two-layer context compaction: Layer 1 offloads and snips locally,
 * Layer 2 triggers a full LLM summary when tokens exceed 80%.
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final double AUTOCOMPACT_THRESHOLD = 0.80;
    static final int SAFETY_MARGIN_AUTO = 13_000;
    static final int SAFETY_MARGIN_MANUAL = 3_000;

    /** Recovery limits applied to the attachment block appended after a Layer 2 summary. */
    public static final int RECOVERY_FILE_LIMIT = 5;
    public static final int RECOVERY_TOKENS_PER_FILE = 5_000;
    public static final int RECOVERY_SKILLS_BUDGET = 25_000;
    public static final int RECOVERY_TOKENS_PER_SKILL = 5_000;
    private static final double RECOVERY_CHARS_PER_TOKEN = 3.5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static final String SUMMARY_SYSTEM_PROMPT = """
            你的任务是对当前对话生成一份详细的摘要，重点关注用户的明确请求和你之前执行的操作。

            在输出最终摘要之前，请先用 <analysis> 标签包裹你的分析思路。在分析中：
            1. 按时间顺序梳理每条消息，识别请求、方法、决策、文件路径、错误与修复、用户反馈。
            2. 复核技术准确性和完整性。

            分析完成后，用 <summary> 标签输出最终摘要。摘要必须保留：
            - 所有被读取、修改或创建的文件路径
            - 关键决策及其理由
            - 当前任务/目标及整体进度
            - 任何待处理的工作或下一步
            - 错误状态及其解决方案
            - 讨论过的重要代码片段或模式

            输出结构：

            <analysis>
            [你的分析过程]
            </analysis>

            <summary>
            [最终精简摘要]
            </summary>""";

    private ContextCompactor() {}

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Check token ratio and trigger Layer 2 auto-compact if needed.
     * Layer 1 is handled separately by {@code ToolResultBudget.apply()}
     * in the agent loop.
     *
     * @return compact log message (empty string if no compaction happened)
     */
    public static String manage(ConversationManager conversationManager, LLMProvider client, int contextWindow, String workDir,
                                AutoCompactTrackingState tracking, RecoveryState recovery, List<Map<String, Object>> toolSchemas) {

        int tokens = estimateTokens(conversationManager.getMessages());
        double ratio = (double) tokens / contextWindow;
        if (ratio > AUTOCOMPACT_THRESHOLD && (tracking == null || !tracking.isTripped())) {
            try {
                String result = autoCompact(conversationManager, client, contextWindow, recovery, toolSchemas);
                if (tracking != null) tracking.reset();
                return result;
            } catch (Exception e) {
                if (tracking != null) tracking.recordFailure();
            }
        }
        return "";
    }

    /** Force a full auto-compact regardless of current token usage. */
    public static String forceCompact(ConversationManager conv, LLMProvider client, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, recovery, toolSchemas);
    }

    /** Estimate the token count for a list of messages using a simple heuristic. */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── Layer 2: Auto-compact ──────────────────────────────────────────

    private static String autoCompact(ConversationManager conversationManager, LLMProvider llmProvider, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        List<Message> messages = conversationManager.getMessages();
        int beforeTokens = estimateTokens(messages);

        String serialized = serializeForSummary(messages, 500);
        String summaryRaw = requestSummary(llmProvider,
                SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized);
        String summaryText = formatCompactSummary(summaryRaw);

        String content = "[对话压缩摘要]\n\n" + summaryText;
        String attachment = buildRecoveryAttachment(recovery, toolSchemas);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        compacted.addAssistantMessage("明白，我会基于以上上下文继续工作。");

        replaceConversation(conversationManager, compacted);

        int afterTokens = estimateTokens(conversationManager.getMessages());
        return String.format("已压缩: %d → %d 估算 token", beforeTokens, afterTokens);
    }

    // ── Post-compact recovery attachment ───────────────────────────────

    /**
     * Render the four-section recovery block that gets appended to the
     * summary user message. Returns "" when there is nothing worth
     * emitting so the caller can keep the summary clean.
     */
    public static String buildRecoveryAttachment(RecoveryState state,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("## 最近读取的文件\n\n")
                  .append("以下是文件读取工具最近返回的快照，如需最新内容请用工具重新读取。\n\n");
                for (var f : files) {
                    String body = truncateByTokens(f.content(), RECOVERY_TOKENS_PER_FILE);
                    sb.append("### ").append(f.path())
                      .append("  (读取时间 ").append(RECOVERY_TS.format(f.timestamp())).append(")\n\n")
                      .append("```\n").append(body);
                    if (!body.endsWith("\n")) sb.append('\n');
                    sb.append("```\n\n");
                }
            }
        }

        if (state != null) {
            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                var section = new StringBuilder();
                section.append("## 激活的技能\n\n")
                       .append("以下技能曾在此会话中被调用，当触发条件满足时请继续遵循各自的 SOP。\n\n");
                int used = 0;
                boolean emitted = false;
                for (var sk : skills) {
                    String body = truncateByTokens(sk.body(), RECOVERY_TOKENS_PER_SKILL);
                    int tokens = approxTokens(body) + approxTokens(sk.name()) + 8;
                    if (used + tokens > RECOVERY_SKILLS_BUDGET) break;
                    used += tokens;
                    section.append("### ").append(sk.name()).append("\n\n")
                           .append(body).append("\n\n");
                    emitted = true;
                }
                if (emitted) sb.append(section);
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## 可用工具\n\n")
              .append("你仍然可以访问以下工具——需要时直接调用：\n\n");
            for (var t : toolSchemas) {
                if (t == null) continue;
                Object nameObj = t.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                if (name.isEmpty()) continue;
                Object descObj = t.get("description");
                String desc = descObj == null ? "" : firstLine(descObj.toString());
                if (!desc.isEmpty()) {
                    sb.append("- ").append(name).append(" — ").append(desc).append('\n');
                } else {
                    sb.append("- ").append(name).append('\n');
                }
            }
            sb.append('\n');
        }

        if (sb.length() == 0) return "";

        sb.append("## 注意\n\n分隔线以上的内容是重构后的摘要。")
          .append("如需获取准确的代码、错误信息或用户输入的原文，请重新读取源文件，")
          .append("不要凭摘要猜测。\n");
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    private static String requestSummary(LLMProvider client, String prompt) {
        List<Message> messages = List.of(new Message("user", prompt));
        BlockingQueue<String> chunkQueue = new LinkedBlockingQueue<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);

        client.streamChat(messages, new StreamCallback() {
            @Override
            public void onChunk(String text, ChunkType type) {
                if (type == ChunkType.TEXT) {
                    chunkQueue.offer(text);
                }
                // ignore THINKING chunks
            }

            @Override
            public void onComplete(String stopReason) {
                done.set(true);
                chunkQueue.offer(""); // sentinel to unblock the loop
            }

            @Override
            public void onError(Throwable t) {
                error.set(t.getMessage());
                done.set(true);
                chunkQueue.offer(""); // sentinel
            }
        }, List.of()); // empty tools list — no tool calls allowed

        StringBuilder summary = new StringBuilder();

        try {
            while (!done.get()) {
                String chunk = chunkQueue.poll(30, TimeUnit.SECONDS);
                if (chunk == null) {
                    throw new RuntimeException("LLM summary timeout");
                }
                if (chunk.isEmpty() && done.get()) break;
                summary.append(chunk);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        if (error.get() != null) {
            throw new RuntimeException("LLM summary failed: " + error.get());
        }

        return summary.toString();
    }

    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        StringBuilder stringBuilder = new StringBuilder();
        for (Message message : messages) {
            stringBuilder.append(String.format("[%s]: %s\n", message.getRole(), nullSafe(message.getContent())));

            if (message.getToolUses() != null) {
                for (ToolUseBlock toolUseBlock : message.getToolUses()) {
                    stringBuilder.append(String.format("[tool_use %s]: %s\n", toolUseBlock.toolName(), toolUseBlock.toolUseId()));
                }
            }
            if (message.getToolResults() != null) {
                for (ToolResultBlock toolResultBlock : message.getToolResults()) {
                    String content = nullSafe(toolResultBlock.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    stringBuilder.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return stringBuilder.toString();
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) (s.length() / RECOVERY_CHARS_PER_TOKEN);
    }

    private static String truncateByTokens(String s, int tokenBudget) {
        if (s == null || s.isEmpty() || tokenBudget <= 0) return s == null ? "" : s;
        if (approxTokens(s) <= tokenBudget) return s;
        int maxChars = (int) (tokenBudget * RECOVERY_CHARS_PER_TOKEN);
        if (maxChars <= 0 || maxChars >= s.length()) return s;
        return s.substring(0, maxChars) + "\n… (content truncated)";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }
}

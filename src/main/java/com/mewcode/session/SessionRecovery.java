package com.mewcode.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles session recovery from JSONL files with resilience
 * against corruption and edge cases.
 *
 * <p>Recovery steps:
 * <ol>
 *   <li>Skip malformed lines (JSON parse failures).</li>
 *   <li>Truncate at the last complete tool_use/tool_result pair;
 *       discard orphaned tool_use messages and everything after.</li>
 *   <li>If estimated tokens exceed 80% of context window,
 *       mark {@code needsCompaction=true}.</li>
 *   <li>If the last message is older than 24 hours,
 *       insert a time-span warning at the beginning.</li>
 * </ol>
 */
public class SessionRecovery {

    /**
     * Result of session recovery.
     */
    public record RecoveryResult(
            List<SessionManager.SessionMessage> messages,
            List<String> warnings,
            boolean needsCompaction
    ) {}

    private static final double TOKEN_RATIO_THRESHOLD = 0.80;
    private static final long STALE_THRESHOLD_HOURS = 24;

    /**
     * Recover a session message list, applying all exception-handling steps.
     *
     * @param rawMessages   the loaded (possibly corrupted) message list
     * @param contextWindow the model's context window size (for token estimation)
     * @return recovery result with cleaned messages and any warnings
     */
    public static RecoveryResult recover(List<SessionManager.SessionMessage> rawMessages,
                                         int contextWindow) {
        List<String> warnings = new ArrayList<>();
        List<SessionManager.SessionMessage> cleaned = new ArrayList<>(rawMessages);

        // Step (a): skip malformed lines — already done by SessionManager.loadSession()

        // Step (b): truncate at the last complete tool_use/tool_result pairing
        cleaned = truncateIncompleteToolCalls(cleaned, warnings);

        // Step (c): check token estimation
        boolean needsCompaction = false;
        long estimatedTokens = estimateTokens(cleaned);
        if (estimatedTokens > (long) (contextWindow * TOKEN_RATIO_THRESHOLD)) {
            needsCompaction = true;
            warnings.add("恢复的会话消息量 (" + estimatedTokens + " 估算 token) 超过上下文窗口的 "
                    + ((int) (TOKEN_RATIO_THRESHOLD * 100)) + "%，建议压缩后继续");
        }

        // Step (d): check time gap
        if (!cleaned.isEmpty()) {
            SessionManager.SessionMessage last = cleaned.get(cleaned.size() - 1);
            if (last.timestamp() > 0) {
                Instant lastTime = Instant.ofEpochSecond(last.timestamp());
                Duration gap = Duration.between(lastTime, Instant.now());
                if (gap.toHours() >= STALE_THRESHOLD_HOURS) {
                    long days = gap.toDays();
                    long hours = gap.toHours() % 24;
                    String gapStr;
                    if (days > 0) {
                        gapStr = days + " 天" + (hours > 0 ? " " + hours + " 小时" : "");
                    } else {
                        gapStr = hours + " 小时";
                    }
                    String warning = "⚠️ 本会话最后一次活动已是 "
                            + gapStr + " 前，模型参数/依赖版本可能已有变化";
                    warnings.add(warning);

                    // Insert time-span warning at the beginning
                    List<SessionManager.SessionMessage> withWarning = new ArrayList<>();
                    withWarning.add(new SessionManager.SessionMessage(
                            "system", warning, Instant.now().getEpochSecond()));
                    withWarning.addAll(cleaned);
                    cleaned = withWarning;
                }
            }
        }

        return new RecoveryResult(cleaned, warnings, needsCompaction);
    }

    /**
     * Truncate at the last point where tool_use/tool_result pairs are balanced.
     * An orphaned tool_use (no matching tool_result) and everything after it is discarded.
     */
    static List<SessionManager.SessionMessage> truncateIncompleteToolCalls(
            List<SessionManager.SessionMessage> messages, List<String> warnings) {

        // We track open tool_use IDs. When we see a tool_result, we remove that ID.
        // After processing all messages, if there are open tool_use IDs, we truncate
        // to before the earliest open tool_use.
        int lastSafeIndex = messages.size(); // exclusive

        // Scan for the pattern: messages with tool_use info need to be tracked.
        // Since we only have role+content+ts from JSONL (not structured tool use IDs),
        // we use a heuristic: any message with role indicating tool usage.
        // The actual structured data (toolUseId, etc.) is in the content JSON.

        // For now, use a simple detection: messages where role starts with "tool_use"
        // or contain structured tool_use in the content.
        record OpenCall(int index, String toolId) {}
        List<OpenCall> openCalls = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            SessionManager.SessionMessage msg = messages.get(i);

            // Detect tool_use: role may be "tool_use", or the content may contain
            // structured tool_use blocks in JSON format
            if (isToolUse(msg)) {
                String toolId = extractToolUseId(msg);
                if (toolId != null) {
                    openCalls.add(new OpenCall(i, toolId));
                }
            }

            // Detect tool_result: close matching open call
            if (isToolResult(msg)) {
                String resultId = extractToolResultId(msg);
                if (resultId != null) {
                    // Remove the matching open call (match by toolUseId)
                    openCalls.removeIf(oc -> resultId.equals(oc.toolId));
                }
            }
        }

        // If there are unmatched tool_use calls, truncate to before the earliest one
        if (!openCalls.isEmpty()) {
            int earliestOrphan = openCalls.stream()
                    .mapToInt(OpenCall::index)
                    .min()
                    .orElse(messages.size());
            int removed = messages.size() - earliestOrphan;
            warnings.add("检测到 " + openCalls.size() + " 个工具调用缺少对应结果，"
                    + "已截断 " + removed + " 条不完整消息");
            return new ArrayList<>(messages.subList(0, earliestOrphan));
        }

        return new ArrayList<>(messages);
    }

    /**
     * Rough token estimation: total characters ÷ 3.5.
     */
    static long estimateTokens(List<SessionManager.SessionMessage> messages) {
        long totalChars = 0;
        for (SessionManager.SessionMessage msg : messages) {
            if (msg.content() != null) {
                totalChars += msg.content().length();
            }
        }
        return (long) (totalChars / 3.5);
    }

    // ---- Detection helpers ----

    static boolean isToolUse(SessionManager.SessionMessage msg) {
        if ("tool_use".equals(msg.role()) || "assistant".equals(msg.role())) {
            String content = msg.content();
            return content != null && (content.contains("\"tool_use\"") || content.contains("\"toolUses\""));
        }
        return false;
    }

    static boolean isToolResult(SessionManager.SessionMessage msg) {
        if ("tool_result".equals(msg.role()) || "user".equals(msg.role())) {
            String content = msg.content();
            return content != null && (content.contains("\"tool_result\"") || content.contains("\"toolResults\""));
        }
        return false;
    }

    static String extractToolUseId(SessionManager.SessionMessage msg) {
        // Simple heuristic: find tool_use id in JSON-like content
        if (msg.content() == null) return null;
        String content = msg.content();
        // Look for "toolUseId": "xxx" or "id": "xxx" patterns
        int idx = content.indexOf("\"toolUseId\"");
        if (idx < 0) return null;
        int colonIdx = content.indexOf(':', idx);
        if (colonIdx < 0) return null;
        int startQuote = content.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = content.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return content.substring(startQuote + 1, endQuote);
    }

    static String extractToolResultId(SessionManager.SessionMessage msg) {
        // Same extraction for tool_result
        return extractToolUseId(msg);
    }

    /**
     * Reconstruct a user-friendly message list suitable for feeding back
     * into ConversationManager.
     */
    public static List<SessionManager.SessionMessage> toConversationMessages(
            RecoveryResult recoveryResult) {
        return recoveryResult.messages();
    }
}

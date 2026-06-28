package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 1 tool-result budget — Design B. Walks the input conversation,
 * decides each tool_result's fate against {@link ContentReplacementState},
 * and returns a NEW {@link ConversationManager} with replacements applied.
 * The input conv is never mutated. {@code state.seenIds} /
 * {@code state.replacements} are mutated to record this turn's decisions;
 * subsequent calls re-apply those decisions verbatim (byte-identical
 * preview strings, no I/O) so the prompt-cache prefix stays stable across
 * turns.
 */
public final class ToolResultBudget {

    /** Per-result spill threshold. */
    public static final int SINGLE_RESULT_LIMIT = 15_000;

    /** Per-message aggregate spill threshold. */
    public static final int MESSAGE_AGGREGATE_LIMIT = 20_000;

    /** Stale-tail snip threshold. */
    public static final int OLD_RESULT_SNIP_CHARS = 2_000;

    /** Recent-window boundary that stale-snip never touches. */
    public static final int KEEP_RECENT_TURNS = 10;

    /** Tool-results spill subdirectory relative to sessionDir. */
    public static final String SPILL_SUBDIR = "tool_results";

    private static final String PERSISTED_TAG_PREFIX = "[Result of ";
    private static final String SNIPPED_TAG_PREFIX = "[Stale output snipped:";

    private ToolResultBudget() {}

    public static ApplyResult apply(
            ConversationManager conversationManager,
            Path sessionDir,
            ContentReplacementState contentReplacementState
    ) {
        List<Message> messages = conversationManager.getMessages();
        if (messages.isEmpty()) {
            return new ApplyResult(conversationManager, List.of());
        }

        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);
        List<ContentReplacementRecord> records = new ArrayList<>();
        List<Message> newHistory = new ArrayList<>(messages.size());

        for (Message message : messages) {
            List<ToolResultBlock> toolResultList = message.getToolResults();
            if (toolResultList == null || toolResultList.isEmpty()) {
                newHistory.add(message);
                continue;
            }

            Map<String, String> decisions = new HashMap<>(toolResultList.size() * 2);
            List<ToolResultBlock> fresh = new ArrayList<>();

            for (ToolResultBlock toolResult : toolResultList) {
                String toolUseId = toolResult.toolUseId();
                String existing = contentReplacementState.replacements().get(toolUseId);
                if (existing != null) {
                    decisions.put(toolUseId, existing);
                    continue;
                }
                if (contentReplacementState.seenIds().contains(toolUseId)) {
                    // Previously passed through — if large, spill now (second-pass spill)
                    if (toolResult.content().length() > SINGLE_RESULT_LIMIT) {
                        String preview = spillAndPreview(spillDir, toolResult);
                        if (preview != null) {
                            contentReplacementState.replacements().put(toolUseId, preview);
                            records.add(ContentReplacementRecord.toolResult(toolUseId, preview));
                            decisions.put(toolUseId, preview);
                        } else {
                            decisions.put(toolUseId, toolResult.content());
                        }
                    } else {
                        decisions.put(toolUseId, toolResult.content());
                    }
                    continue;
                }
                if (isAlreadyReplaced(toolResult.content())) {
                    // External pre-tagged content — freeze as the tag itself.
                    contentReplacementState.seenIds().add(toolUseId);
                    contentReplacementState.replacements().put(toolUseId, toolResult.content());
                    decisions.put(toolUseId, toolResult.content());
                    records.add(ContentReplacementRecord.toolResult(toolUseId, toolResult.content()));
                    continue;
                }
                fresh.add(toolResult);
            }

            // Pass 1: mark large fresh results as seen, pass through full content.
            // Defer spill to the next turn (second-pass spill in seenIds branch),
            // so the model sees the content at least once.
            Set<String> passedThroughP1 = new HashSet<>();
            for (ToolResultBlock toolResultBlock : fresh) {
                if (toolResultBlock.content().length() <= SINGLE_RESULT_LIMIT) continue;
                contentReplacementState.seenIds().add(toolResultBlock.toolUseId());
                decisions.put(toolResultBlock.toolUseId(), toolResultBlock.content());
                passedThroughP1.add(toolResultBlock.toolUseId());
            }

            // Pass 2: aggregate-spill the largest remaining fresh candidates
            // until total ≤ MESSAGE_AGGREGATE_LIMIT.
            // Exclude passedThroughP1 results — their large content will be
            // handled by second-pass spill on the next turn.
            List<ToolResultBlock> remaining = new ArrayList<>();
            for (ToolResultBlock toolResultBlock : fresh) {
                if (!passedThroughP1.contains(toolResultBlock.toolUseId())) {
                    remaining.add(toolResultBlock);
                }
            }

            int total = 0;
            for (var entry : decisions.entrySet()) {
                if (!passedThroughP1.contains(entry.getKey())) {
                    total += entry.getValue().length();
                }
            }
            for (ToolResultBlock toolResultBlock : remaining) total += toolResultBlock.content().length();

            if (total > MESSAGE_AGGREGATE_LIMIT && !remaining.isEmpty()) {
                List<ToolResultBlock> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt((ToolResultBlock t) -> t.content().length()).reversed());
                for (ToolResultBlock toolResultBlock : sorted) {
                    if (total <= MESSAGE_AGGREGATE_LIMIT) break;
                    String preview = spillAndPreview(spillDir, toolResultBlock);
                    if (preview == null) {
                        contentReplacementState.seenIds().add(toolResultBlock.toolUseId());
                        decisions.put(toolResultBlock.toolUseId(), toolResultBlock.content());
                        continue;
                    }
                    decisions.put(toolResultBlock.toolUseId(), preview);
                    contentReplacementState.seenIds().add(toolResultBlock.toolUseId());
                    contentReplacementState.replacements().put(toolResultBlock.toolUseId(), preview);
                    records.add(ContentReplacementRecord.toolResult(toolResultBlock.toolUseId(), preview));
                    total -= toolResultBlock.content().length() - preview.length();
                }
            }

            // Freeze remaining fresh as "seen but not replaced".
            for (ToolResultBlock toolResultBlock : fresh) {
                if (decisions.containsKey(toolResultBlock.toolUseId())) continue;
                contentReplacementState.seenIds().add(toolResultBlock.toolUseId());
                decisions.put(toolResultBlock.toolUseId(), toolResultBlock.content());
            }

            // Materialize new tool_results in original order.
            List<ToolResultBlock> newResults = new ArrayList<>(toolResultList.size());
            for (ToolResultBlock toolResultBlock : toolResultList) {
                newResults.add(new ToolResultBlock(
                        toolResultBlock.toolUseId(),
                        decisions.get(toolResultBlock.toolUseId()),
                        toolResultBlock.isError()
                ));
            }
            newHistory.add(copyMessageWithResults(message, newResults));
        }

        // Pass 3: stale-snip on the new history.
        newHistory = snipStale(newHistory);

        return new ApplyResult(buildManager(newHistory), records);
    }

    private static String buildSpillPreview(int originalSize, Path path) {
        return "[Result of " + originalSize + " chars saved to " + path + "]";
    }

    private static String spillAndPreview(Path spillDir, ToolResultBlock toolResultBlock) {
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(toolResultBlock.toolUseId());
            if (Files.exists(file) && Files.size(file) == toolResultBlock.content().length()) {
                return buildSpillPreview(toolResultBlock.content().length(), file);
            }
            Files.writeString(file, toolResultBlock.content());
            return buildSpillPreview(toolResultBlock.content().length(), file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAlreadyReplaced(String s) {
        return s != null && (s.startsWith(PERSISTED_TAG_PREFIX) || s.startsWith(SNIPPED_TAG_PREFIX));
    }

    private static List<Message> snipStale(List<Message> messages) {
        int totalTurns = 0;
        for (Message m : messages) {
            if ("assistant".equals(m.getRole()) && (m.getToolUses() == null || m.getToolUses().isEmpty())) {
                totalTurns++;
            }
        }
        if (totalTurns <= KEEP_RECENT_TURNS) return messages;

        List<Message> out = new ArrayList<>(messages.size());
        int turnsSeen = 0;
        int oldBoundary = totalTurns - KEEP_RECENT_TURNS;

        for (Message message : messages) {
            if ("assistant".equals(message.getRole()) && (message.getToolUses() == null || message.getToolUses().isEmpty())) {
                turnsSeen++;
            }
            if (turnsSeen > oldBoundary || message.getToolResults() == null || message.getToolResults().isEmpty()) {
                out.add(message);
                continue;
            }
            List<ToolResultBlock> newResults = new ArrayList<>();
            boolean changed = false;
            for (ToolResultBlock toolResultBlock : message.getToolResults()) {
                if (isAlreadyReplaced(toolResultBlock.content()) || toolResultBlock.content().length() <= OLD_RESULT_SNIP_CHARS) {
                    newResults.add(toolResultBlock);
                    continue;
                }
                newResults.add(new ToolResultBlock(
                        toolResultBlock.toolUseId(),
                        "[Stale output snipped: " + toolResultBlock.content().length() + " chars]",
                        toolResultBlock.isError()
                ));
                changed = true;
            }
            out.add(changed ? copyMessageWithResults(message, newResults) : message);
        }
        return out;
    }

    private static Message copyMessageWithResults(Message src, List<ToolResultBlock> newResults) {
        Message copy = new Message(src.getRole(), src.getContent());
        copy.setThinkingBlocks(src.getThinkingBlocks());
        copy.setToolUses(src.getToolUses());
        copy.setToolResults(newResults);
        // Legacy bridge
        if (!newResults.isEmpty()) {
            copy.setToolUseId(newResults.getFirst().toolUseId());
        }
        return copy;
    }

    /**
     * Build a fresh ConversationManager from a message list.
     * Skips system messages (the new manager already has one from its constructor).
     */
    private static ConversationManager buildManager(List<Message> messages) {
        ConversationManager conversationManager = new ConversationManager();
        for (Message message : messages) {
            // Skip system message — the new manager's constructor already added one
            if ("system".equals(message.getRole())) continue;
            boolean hasToolUses = message.getToolUses() != null && !message.getToolUses().isEmpty();
            boolean hasToolResults = message.getToolResults() != null && !message.getToolResults().isEmpty();
            if (hasToolUses) {
                conversationManager.addAssistantFull(message.getContent(), message.getThinkingBlocks(), message.getToolUses());
            } else if (hasToolResults) {
                conversationManager.addToolResultsMessage(message.getToolResults());
            } else if ("user".equals(message.getRole())) {
                conversationManager.addUserMessage(message.getContent());
            } else if ("assistant".equals(message.getRole())) {
                conversationManager.addAssistantMessage(message.getContent());
            }
        }
        return conversationManager;
    }
}

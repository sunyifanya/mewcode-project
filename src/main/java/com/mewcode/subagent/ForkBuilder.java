package com.mewcode.subagent;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a forked conversation from the parent's message history.
 *
 * <p>Three steps:</p>
 * <ol>
 *   <li>Deep-copy all parent messages</li>
 *   <li>Patch unterminated tool_use blocks with placeholder tool_results</li>
 *   <li>Append the Fork Boilerplate + task as a user message</li>
 * </ol>
 */
public final class ForkBuilder {

    /** Tag used to detect nested fork attempts in conversation history. */
    public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    /** Full Fork Boilerplate injected as the first user message. */
    public static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. When done, produce a complete final response. Do not self-limit length.
            </fork_boilerplate>""";

    private ForkBuilder() {}

    /**
     * Build a forked ConversationManager from the parent's message history.
     *
     * @param parent the parent agent's conversation
     * @return a new ConversationManager with copied messages + task
     */
    public static ConversationManager buildForkedConversation(ConversationManager parent) {
        ConversationManager forked = new ConversationManager();

        List<Message> source = parent.getMessages();
        List<Message> copied = deepCopyMessages(source);

        // Collect tool_use_ids that have corresponding tool_results
        Set<String> resolvedIds = new HashSet<>();
        for (Message msg : copied) {
            if (msg.getToolResults() != null) {
                for (ToolResultBlock trb : msg.getToolResults()) {
                    resolvedIds.add(trb.toolUseId());
                }
            }
        }

        // Replay messages, patching unterminated tool_use blocks
        for (Message msg : copied) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                // Check which tool_use_ids are unresolved
                List<ToolUseBlock> unresolved = new ArrayList<>();
                for (ToolUseBlock tu : msg.getToolUses()) {
                    if (!resolvedIds.contains(tu.toolUseId())) {
                        unresolved.add(tu);
                    }
                }

                forked.getMessagesMutable().add(msg);

                // Patch: add placeholder tool_results for unresolved tool_use blocks
                if (!unresolved.isEmpty()) {
                    List<ToolResultBlock> placeholders = new ArrayList<>();
                    for (ToolUseBlock tu : unresolved) {
                        placeholders.add(new ToolResultBlock(
                                tu.toolUseId(),
                                "(tool execution interrupted by fork)",
                                false));
                    }
                    Message placeholderMsg = new Message("user", "");
                    placeholderMsg.setToolResults(placeholders);
                    forked.getMessagesMutable().add(placeholderMsg);
                }
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                forked.getMessagesMutable().add(msg);
            } else if ("assistant".equals(msg.getRole())) {
                forked.getMessagesMutable().add(msg);
            } else if ("user".equals(msg.getRole()) || "system".equals(msg.getRole())) {
                forked.getMessagesMutable().add(msg);
            }
        }

        return forked;
    }

    /**
     * Build the initial user message for the forked agent.
     */
    public static String buildTaskMessage(String task) {
        return FORK_BOILERPLATE + "\n\nYour task:\n" + task;
    }

    /**
     * Check if a conversation contains the Fork Boilerplate tag,
     * indicating it's already a forked context (nested fork detection).
     */
    public static boolean containsForkBoilerplate(ConversationManager conv) {
        for (Message msg : conv.getMessages()) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a list of messages contains the Fork Boilerplate tag.
     */
    public static boolean containsForkBoilerplate(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return true;
            }
        }
        return false;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Deep-copy a list of messages so the forked conversation is independent.
     */
    private static List<Message> deepCopyMessages(List<Message> source) {
        List<Message> result = new ArrayList<>();
        for (Message msg : source) {
            Message copy = new Message(msg.getRole(), msg.getContent());

            // Copy thinking blocks
            if (msg.getThinkingBlocks() != null) {
                copy.setThinkingBlocks(new ArrayList<>(msg.getThinkingBlocks()));
            }

            // Copy tool uses
            if (msg.getToolUses() != null) {
                List<ToolUseBlock> toolUsesCopy = new ArrayList<>();
                for (ToolUseBlock tu : msg.getToolUses()) {
                    toolUsesCopy.add(new ToolUseBlock(
                            tu.toolUseId(),
                            tu.toolName(),
                            tu.arguments() != null ? new java.util.LinkedHashMap<>(tu.arguments()) : null));
                }
                copy.setToolUses(toolUsesCopy);
            }

            // Copy tool results
            if (msg.getToolResults() != null) {
                List<ToolResultBlock> toolResultsCopy = new ArrayList<>();
                for (ToolResultBlock trb : msg.getToolResults()) {
                    toolResultsCopy.add(new ToolResultBlock(
                            trb.toolUseId(), trb.content(), trb.isError()));
                }
                copy.setToolResults(toolResultsCopy);
            }

            result.add(copy);
        }
        return result;
    }
}

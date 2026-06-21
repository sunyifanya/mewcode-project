package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;

import java.util.List;

/**
 * Result of {@link ToolResultBudget#apply}: a freshly-built
 * {@link ConversationManager} with replacements applied (the input conv is
 * never mutated) plus the list of decisions newly made on this call.
 */
public record ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords) {}

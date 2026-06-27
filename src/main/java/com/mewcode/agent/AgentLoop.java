package com.mewcode.agent;

import com.mewcode.compact.AutoCompactTrackingState;
import com.mewcode.compact.ContextCompactor;
import com.mewcode.compact.RecoveryState;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.SystemPromptBuilder;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.memory.MemoryExtractor;
import com.mewcode.memory.MemoryManager;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.permission.PermissionResult;
import com.mewcode.permission.RuleEngine;
import com.mewcode.permission.RuleEntry;
import com.mewcode.provider.LLMProvider;
import com.mewcode.session.SessionManager;
import com.mewcode.skill.SkillForkHost;
import com.mewcode.skill.SkillHost;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.toolresult.ApplyResult;
import com.mewcode.toolresult.ContentReplacementRecord;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.toolresult.ReplacementRecordsIO;
import com.mewcode.toolresult.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ReAct-style agent loop: Think → Act → Observe → repeat until done.
 * Runs on its own thread (implements Runnable). Pushes {@link AgentEvent}
 * instances to a {@link BlockingQueue} consumed by the UI thread.
 *
 * <p>Each iteration applies two-layer context compaction:
 * <ol>
 *   <li>Layer 1 — ToolResultBudget replaces large/stale tool results with previews</li>
 *   <li>Layer 2 — ContextCompactor triggers an LLM summary when tokens exceed 80%</li>
 * </ol>
 */
public class AgentLoop implements Runnable, SkillHost, SkillForkHost {

    private final LLMProvider provider;
    private final ToolRegistry toolRegistry;
    private final ConversationManager conversation;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final int maxIterations;
    private final long streamTimeoutMs;
    private final ToolExecutionStrategy executionStrategy;
    private final PermissionChecker permissionChecker;

    private volatile boolean cancelled;
    private volatile boolean planMode;

    // ---- permission human-in-the-loop ----

    /** Set to true while the agent is blocked waiting for user permission input. */
    private volatile boolean waitingForPermission;

    /** Response queue for the blocking waitForPermission call. */
    private final BlockingQueue<PermissionResponse> permissionResponseQueue = new LinkedBlockingQueue<>();

    // ---- context compaction state ----

    private final AutoCompactTrackingState compactTracking = new AutoCompactTrackingState();
    private final RecoveryState recoveryState = new RecoveryState();
    private ContentReplacementState replacementState = new ContentReplacementState();

    private String workingDirectory;
    private int contextWindow = 200_000;
    private String sessionId;

    // ---- skill system ----

    /** Active skill name → SOP body. Multi-skill support. */
    private final Map<String, String> activeSkills = new LinkedHashMap<>();

    /** Current tool name filter from the last-activated skill. null = no filter. */
    private Predicate<String> skillToolFilter;

    // ---- memory extraction ----
    private MemoryExtractor memoryExtractor;
    private MemoryManager memoryManager;
    private LLMProvider providerForMemory;

    public AgentLoop(LLMProvider provider, ToolRegistry toolRegistry,
                     ConversationManager conversation,
                     BlockingQueue<AgentEvent> eventQueue,
                     int maxIterations, int streamTimeoutSeconds,
                     PermissionChecker permissionChecker) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.conversation = conversation;
        this.eventQueue = eventQueue;
        this.maxIterations = maxIterations;
        this.streamTimeoutMs = streamTimeoutSeconds * 1000L;
        this.permissionChecker = permissionChecker;
        this.executionStrategy = new ToolExecutionStrategy(permissionChecker);
        this.workingDirectory = System.getProperty("user.dir", ".");
    }

    /**
     * Signal the loop to stop at the next check point.
     */
    public void cancel() {
        this.cancelled = true;
        // Wake up permission wait if blocked
        permissionResponseQueue.offer(PermissionResponse.DENY);
    }

    /**
     * Enable / disable Plan Mode (read-only tools only).
     */
    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
        this.permissionChecker.setPlanMode(planMode);
    }

    public boolean isPlanMode() {
        return planMode;
    }

    /**
     * Whether the agent is currently blocked waiting for a permission response.
     * The UI layer checks this to dispatch input to permission handling.
     */
    public boolean isWaitingForPermission() {
        return waitingForPermission;
    }

    /**
     * Called from the UI thread with the user's permission decision.
     *
     * @param choice "Y" = once, "A" = always, anything else = deny
     */
    public void respondToPermission(String choice) {
        String upper = choice != null ? choice.trim().toUpperCase() : "";
        switch (upper) {
            case "Y" -> permissionResponseQueue.offer(PermissionResponse.ALLOW);
            case "A" -> permissionResponseQueue.offer(PermissionResponse.ALLOW_ALWAYS);
            default -> permissionResponseQueue
                    .offer(PermissionResponse.DENY);
        }
    }

    // ---- compaction accessors ----

    public RecoveryState getRecoveryState() { return recoveryState; }
    public ContentReplacementState getReplacementState() { return replacementState; }
    public void setReplacementState(ContentReplacementState state) { this.replacementState = state; }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        // Auto-load replacement state for resumed sessions so spill placeholders
        // from previous runs are recognized without re-spilling.
        if (workingDirectory != null && sessionId != null && replacementState.seenIds().isEmpty()) {
            try {
                Path sessionDir = Paths.get(workingDirectory, ".mewcode", "session_save_content", sessionId);
                List<ContentReplacementRecord> records = ReplacementRecordsIO.load(sessionDir);
                if (!records.isEmpty()) {
                    for (ContentReplacementRecord r : records) {
                        replacementState.seenIds().add(r.toolUseId());
                        replacementState.replacements().put(r.toolUseId(), r.replacement());
                    }
                }
            } catch (Exception ignored) {
                // Best-effort: if file doesn't exist or is corrupt, start fresh
            }
        }
    }

    public void setMemoryExtractor(MemoryExtractor memoryExtractor) {
        this.memoryExtractor = memoryExtractor;
    }

    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    public void setProviderForMemory(LLMProvider provider) {
        this.providerForMemory = provider;
    }

    // ---- SkillHost implementation ----

    @Override
    public void activateSkill(String name, String body) {
        activeSkills.put(name, body);
    }

    @Override
    public void setToolFilter(Predicate<String> filter) {
        this.skillToolFilter = filter;
    }

    @Override
    public ToolRegistry toolRegistry() {
        return this.toolRegistry;
    }

    @Override
    public void recordSkillInvocation(String name, String body) {
        // Record the skill SOP in recovery state for compact recovery
        recoveryState.recordSkillInvocation(name, body);
    }

    // ---- SkillForkHost implementation ----

    @Override
    public List<Message> snapshotParentMessages() {
        var msgs = conversation.getMessages();
        return msgs != null ? List.copyOf(msgs) : List.of();
    }

    @Override
    public String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model) {
        // If a different model is requested, warn but use current provider
        if (model != null && !model.isBlank()) {
            String currentModel = "";
            offerEvent(AgentEvent.of(AgentEventType.PROGRESS)
                    .message("[system warning] Skill requested model '" + model
                            + "', but fork sub-agents always use the current provider's model. "
                            + "Multi-provider model switching is not yet implemented.")
                    .build());
        }

        // Create a fresh ConversationManager for the sub-agent
        var subConv = new ConversationManager();
        // Inject seed messages as history
        for (var msg : seed) {
            var messagesMutable = subConv.getMessagesMutable();
            messagesMutable.add(msg);
        }

        // Inject the skill SOP as a system-reminder
        if (body != null && !body.isBlank()) {
            var messagesMutable = subConv.getMessagesMutable();
            messagesMutable.add(new Message("user",
                    "<system-reminder>\n## Active Skill\n\n" + body + "\n</system-reminder>"));
        }

        // Tool whitelist for sub-agent
        var subToolFilter = (allowedTools != null && !allowedTools.isEmpty())
                ? (java.util.function.Predicate<String>) allowedTools::contains
                : null;

        // Create a sub-agent loop
        var subEventQueue = new LinkedBlockingQueue<AgentEvent>(256);
        var subLoop = new AgentLoop(
                provider,
                toolRegistry,
                subConv,
                subEventQueue,
                maxIterations,
                (int) (streamTimeoutMs / 1000),
                permissionChecker
        );
        subLoop.setPlanMode(false);
        subLoop.setWorkingDirectory(workingDirectory);
        subLoop.setSessionId(sessionId);
        subLoop.setContextWindow(contextWindow);
        subLoop.setReplacementState(this.replacementState.copy());

        // Apply tool filter (excluding Skill tool from filtering)
        if (subToolFilter != null) {
            subLoop.setToolFilter(subToolFilter);
        }

        // Run the sub-agent synchronously on a new thread
        Thread subThread = new Thread(subLoop, "skill-fork-agent");
        subThread.start();

        // Collect the final text output
        var output = new StringBuilder();
        boolean hadError = false;
        String errorMsg = "";

        try {
            while (true) {
                var event = subEventQueue.poll(streamTimeoutMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (event == null) {
                    hadError = true;
                    errorMsg = "Fork sub-agent timed out after " + (streamTimeoutMs / 1000) + "s";
                    break;
                }

                switch (event.getType()) {
                    case TEXT_DELTA -> {
                        if (event.getText() != null) {
                            output.append(event.getText());
                        }
                    }
                    case ERROR -> {
                        hadError = true;
                        errorMsg = event.getMessage() != null ? event.getMessage() : "Unknown error";
                    }
                    case LOOP_FINISHED -> {
                        return output.toString().trim();
                    }
                    case CANCELLED -> {
                        hadError = true;
                        errorMsg = "Fork sub-agent was cancelled";
                    }
                    default -> {}
                }

                if (hadError) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hadError = true;
            errorMsg = "Fork sub-agent was interrupted";
        }

        if (hadError) {
            subLoop.cancel();
            return "[fork error] " + errorMsg;
        }

        subLoop.cancel();
        return output.toString().trim();
    }

    // ---- active skills accessors ----

    /** Get a copy of the currently active skills map (name → body). */
    public Map<String, String> getActiveSkills() {
        return new LinkedHashMap<>(activeSkills);
    }

    /** Get the active skills context string for system prompt injection. */
    public String getActiveSkillsContext() {
        if (activeSkills.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        var sorted = activeSkills.keySet().stream().sorted().toList();
        for (var name : sorted) {
            sb.append("### ").append(name).append("\n");
            sb.append(activeSkills.get(name)).append("\n\n");
        }
        return sb.toString();
    }

    /** Clear all activated skills (called by /clear). */
    public void clearActiveSkills() {
        activeSkills.clear();
        skillToolFilter = null;
    }

    // ---- compaction helpers (used by /compact command) ----

    public AutoCompactTrackingState getCompactTracking() { return compactTracking; }
    public LLMProvider getProvider() { return provider; }
    public ConversationManager getConversation() { return conversation; }
    public int getContextWindow() { return contextWindow; }

    @Override
    public void run() {
        // Clear loop-scoped rules from the previous run
        permissionChecker.getRuleEngine().clearLoopRules();

        offerEvent(AgentEvent.of(AgentEventType.LOOP_STARTED).build());

        try {
            for (int iter = 0; iter < maxIterations; iter++) {
                // ---- check cancellation ----
                if (cancelled) {
                    offerEvent(AgentEvent.of(AgentEventType.CANCELLED)
                            .message("用户取消").build());
                    return;
                }

                // ---- progress ----
                offerEvent(AgentEvent.of(AgentEventType.PROGRESS)
                        .message("第 " + (iter + 1) + "/" + maxIterations + " 轮").build());

                // ---- context compaction ----

                // Inject active skills into conversation (per-turn)
                if (!activeSkills.isEmpty()) {
                    conversation.setActiveSkillsContent(getActiveSkillsContext());
                }

                // Build tool schemas for this iteration (for Layer 2 recovery attachment)
                List<Map<String, Object>> toolSchemas = buildToolSchemas();

                // Layer 2: Auto-compact check (runs FIRST — may replace conversation in-place)
                String compactMsg = ContextCompactor.manage(
                        conversation, provider, contextWindow, workingDirectory,
                        compactTracking, recoveryState, toolSchemas);
                if (!compactMsg.isEmpty()) {
                    offerEvent(AgentEvent.compactEvent(compactMsg));
                }

                // Layer 1: Apply tool-result budget (runs AFTER Layer 2 — reads from
                // the possibly-compacted conversation, returns a fresh apiConv)
                Path sessionDir = Paths.get(workingDirectory, ".mewcode", "session_save_content",
                        sessionId != null ? sessionId : "_unknown");
                ApplyResult applied = ToolResultBudget.apply(conversation, sessionDir, replacementState);
                if (!applied.newRecords().isEmpty()) {
                    try {
                        ReplacementRecordsIO.append(sessionDir, applied.newRecords());
                    } catch (Exception ignored) {
                        // Best-effort transcript persistence
                    }
                    // Log Layer 1 activity
                    int spilled = 0, snipped = 0;
                    for (var r : applied.newRecords()) {
                        if (r.replacement().startsWith("[Result of ")) spilled++;
                        else if (r.replacement().startsWith("[Stale output snipped:")) snipped++;
                    }
                    if (spilled > 0 || snipped > 0) {
                        var parts = new ArrayList<String>();
                        if (spilled > 0) parts.add(String.format("spilled %d tool result(s)", spilled));
                        if (snipped > 0) parts.add(String.format("snipped %d stale result(s)", snipped));
                        offerEvent(AgentEvent.compactEvent(String.join("; ", parts)));
                    }
                }

                // ---- call LLM ----
                StreamingCollector collector = new StreamingCollector(eventQueue);
                List<Tool> activeTools = planMode
                        ? toolRegistry.getReadOnlyTools()
                        : toolRegistry.getAllTools();

                if (activeTools.isEmpty() && planMode) {
                    activeTools = toolRegistry.getAllTools();
                }

                // Use cleaned messages from Layer 1 for the API call
                List<Message> apiMessages = applied.apiConv().getMessages(iter, planMode);
                provider.streamChat(apiMessages, collector, activeTools);

                boolean completed = collector.awaitCompletion(streamTimeoutMs);
                if (!completed) {
                    offerEvent(AgentEvent.of(AgentEventType.ERROR)
                            .message("LLM 流超时（" + (streamTimeoutMs / 1000) + " 秒）").build());
                    return;
                }

                // ---- check stream error ----
                Throwable streamErr = collector.getError();
                if (streamErr != null) {
                    String errMsg = streamErr.getMessage() != null ? streamErr.getMessage() : "";
                    // Context-too-long recovery
                    if ((errMsg.contains("context") || errMsg.contains("too long")
                            || errMsg.contains("prompt"))
                            && compactTracking.isTripped()) {
                        try {
                            forceCompact();
                        } catch (Exception ignored) {}
                        continue;
                    }
                    return;
                }

                // ---- emit token usage ----
                int inputTokens = collector.getInputTokens();
                int outputTokens = collector.getOutputTokens();
                long cacheCreate = collector.getCacheCreationTokens();
                long cacheRead = collector.getCacheReadTokens();
                if (inputTokens > 0 || outputTokens > 0 || cacheCreate > 0 || cacheRead > 0) {
                    offerEvent(AgentEvent.of(AgentEventType.TOKEN_USAGE)
                            .cacheCreationTokens(cacheCreate)
                            .cacheReadTokens(cacheRead)
                            .build());
                }

                // ---- check stop reason ----
                StopReason stopReason = StopReason.fromString(collector.getStopReason());
                if (stopReason != StopReason.TOOL_USE) {
                    String finalText = collector.getFullText().trim();
                    if (!finalText.isEmpty()) {
                        conversation.addAssistantMessage(finalText);
                        // Save assistant message to session JSONL
                        if (workingDirectory != null && sessionId != null) {
                            SessionManager.saveMessage(workingDirectory, sessionId,
                                    "assistant", finalText);
                        }
                    }
                    // ---- memory extraction tick ----
                    offerEvent(AgentEvent.of(AgentEventType.MEMORY_TICK).build());
                    if (memoryExtractor != null && memoryManager != null
                            && memoryManager.shouldExtract() && providerForMemory != null) {
                        memoryExtractor.extractAsync(providerForMemory, conversation, memoryManager);
                    }
                    offerEvent(AgentEvent.of(AgentEventType.LOOP_FINISHED).build());
                    return;
                }

                // ---- model wants tools — validate them ----
                List<ToolCall> toolCalls = collector.getToolCalls();

                if (toolCalls.isEmpty()) {
                    String text = collector.getFullText().trim();
                    if (!text.isEmpty()) {
                        conversation.addAssistantMessage(text);
                    }
                    offerEvent(AgentEvent.of(AgentEventType.LOOP_FINISHED).build());
                    return;
                }

                // Check for unknown tools
                for (ToolCall tc : toolCalls) {
                    if (toolRegistry.get(tc.getName()) == null) {
                        offerEvent(AgentEvent.of(AgentEventType.UNKNOWN_TOOL)
                                .toolName(tc.getName())
                                .callId(tc.getId())
                                .message("未知工具: " + tc.getName()).build());
                        return;
                    }
                }

                // ---- add tool_use message to conversation ----
                conversation.addToolCallMessage(collector.getFullText().trim(), toolCalls);
                // Save to session JSONL
                if (workingDirectory != null && sessionId != null) {
                    SessionManager.saveMessage(workingDirectory, sessionId,
                            "assistant", collector.getFullText().trim());
                }

                // ---- permission pre-check ----
                List<ToolCall> executableCalls = new ArrayList<>();
                Map<Integer, ToolResult> preResolved = new LinkedHashMap<>();

                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolCall tc = toolCalls.get(i);
                    Tool tool = toolRegistry.get(tc.getName());
                    PermissionResult pr = permissionChecker.check(tool, tc.getInput());

                    switch (pr.getDecision()) {
                        case ALLOW -> executableCalls.add(tc);
                        case DENY -> preResolved.put(i, new ToolResult(false,
                                "权限拒绝: " + pr.getReason() + "\n提示: " + pr.getHint(),
                                "PERMISSION_DENIED"));
                        case ASK -> {
                            // Blocking human-in-the-loop
                            PermissionResponse userDecision = waitForPermission(tc, pr);
                            if (userDecision == PermissionResponse.ALLOW
                                    || userDecision == PermissionResponse.ALLOW_ALWAYS) {
                                executableCalls.add(tc);
                                // Add rule for future calls
                                String keyParam = PermissionChecker.extractContent(tc.getName(), tc.getInput());
                                if (keyParam == null) {
                                    keyParam = tc.getName(); // fallback: tool-level wildcard
                                }
                                String escapedKey = RuleEngine.escapeGlob(keyParam);
                                if (userDecision == PermissionResponse.ALLOW_ALWAYS) {
                                    // Session-scoped "don't ask again"
                                    permissionChecker.addAllowAlways(tc.getName(), keyParam);
                                } else {
                                    // One-time — loop scope
                                    permissionChecker.getRuleEngine().addLoopRule(
                                            new RuleEntry(tc.getName(), escapedKey, RuleEntry.RuleEffect.ALLOW));
                                }
                            } else {
                                preResolved.put(i, new ToolResult(false,
                                        "用户拒绝此项操作",
                                        "PERMISSION_DENIED"));
                            }
                        }
                    }
                }

                // ---- execute tools (read-only concurrent, side-effects sequential) ----
                List<ToolResult> execResults = executionStrategy.execute(executableCalls, toolRegistry);

                // ---- record file reads for compaction recovery ----
                for (int i = 0; i < executableCalls.size(); i++) {
                    ToolCall tc = executableCalls.get(i);
                    // Index into execResults matches executableCalls order
                    if (i < execResults.size()) {
                        ToolResult result = execResults.get(i);
                        if (result.isSuccess() && isFileReadingTool(tc.getName())) {
                            String path = extractPath(tc);
                            if (path != null) {
                                recoveryState.recordFileRead(path, result.getContent());
                            }
                        }
                    }
                }

                // ---- merge results in original order ----
                List<ToolResult> results = new ArrayList<>(toolCalls.size());
                int execIdx = 0;
                for (int i = 0; i < toolCalls.size(); i++) {
                    if (preResolved.containsKey(i)) {
                        results.add(preResolved.get(i));
                    } else {
                        results.add(execResults.get(execIdx++));
                    }
                }

                // ---- push results to UI and conversation (batched into one message) ----
                List<ToolResultBlock> resultBlocks = new ArrayList<>();
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolCall tc = toolCalls.get(i);
                    ToolResult result = results.get(i);

                    offerEvent(AgentEvent.of(AgentEventType.TOOL_CALL_RESULT)
                            .toolName(tc.getName())
                            .callId(tc.getId())
                            .toolResult(result)
                            .build());

                    resultBlocks.add(new ToolResultBlock(tc.getId(), result.getContent(), !result.isSuccess()));
                }
                conversation.addToolResultsMessage(resultBlocks);

                // Persist tool_results to session JSONL so /resume can
                // reconstruct the full tool-call chain.
                if (workingDirectory != null && sessionId != null) {
                    for (ToolResultBlock block : resultBlocks) {
                        SessionManager.saveMessage(workingDirectory, sessionId,
                                "tool_result", block.content(), block.toolUseId(), block.isError());
                    }
                }
            }

            // Hit iteration limit
            offerEvent(AgentEvent.of(AgentEventType.ERROR)
                    .message("已达迭代上限（" + maxIterations + " 轮），循环终止").build());

        } catch (Exception e) {
            offerEvent(AgentEvent.of(AgentEventType.ERROR)
                    .message("Agent Loop 异常: " + e.getMessage()).build());
        }
    }

    /**
     * Force a Layer 2 compaction and reset the circuit breaker.
     * Called by error recovery and /compact command.
     */
    public String forceCompact() {
        List<Map<String, Object>> toolSchemas = buildToolSchemas();
        String result = ContextCompactor.forceCompact(
                conversation, provider, contextWindow, recoveryState, toolSchemas);
        compactTracking.reset();
        return result;
    }

    // ---- helper methods ----

    private List<Map<String, Object>> buildToolSchemas() {
        List<Tool> activeTools = planMode
                ? toolRegistry.getReadOnlyTools()
                : toolRegistry.getAllTools();
        if (activeTools.isEmpty() && planMode) {
            activeTools = toolRegistry.getAllTools();
        }

        // Apply skill tool filter (Skill tool always passes through)
        if (skillToolFilter != null) {
            activeTools = activeTools.stream()
                    .filter(t -> "Skill".equals(t.getName()) || skillToolFilter.test(t.getName()))
                    .toList();
        }

        return toolRegistry.toApiFormat(activeTools);
    }

    private static boolean isFileReadingTool(String toolName) {
        return "ReadFile".equals(toolName) || "read".equals(toolName);
    }

    private static String extractPath(ToolCall tc) {
        if (tc.getInput() == null) return null;
        Object path = tc.getInput().get("file_path");
        if (path == null) path = tc.getInput().get("path");
        return path != null ? path.toString() : null;
    }

    /**
     * Block the agent thread until the user responds via the UI.
     */
    private PermissionResponse waitForPermission(ToolCall tc, PermissionResult checkResult) {
        waitingForPermission = true;
        try {
            offerEvent(AgentEvent.of(AgentEventType.PERMISSION_REQUIRED)
                    .permissionToolCall(tc)
                    .toolName(tc.getName())
                    .callId(tc.getId())
                    .message(checkResult.getReason())
                    .build());

            // Block until UI calls respondToPermission()
            return permissionResponseQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PermissionResponse.DENY;
        } finally {
            waitingForPermission = false;
        }
    }

    private void offerEvent(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

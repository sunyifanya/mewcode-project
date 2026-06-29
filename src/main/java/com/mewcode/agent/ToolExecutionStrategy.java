package com.mewcode.agent;

import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executes a batch of tool calls with ordering rules:
 * <ol>
 *   <li>All READ tools run concurrently in a bounded thread pool.</li>
 *   <li>After all read-only complete, WRITE and COMMAND tools run sequentially
 *       in the order they appeared in the original list.</li>
 * </ol>
 * Results are assembled in the original ToolCall order so the caller can
 * match each ToolCall to its ToolResult by index.
 */
public class ToolExecutionStrategy {

    private static final int MAX_THREADS = 10;

    private final int threadCount;
    private final PermissionChecker permissionChecker;

    public ToolExecutionStrategy(PermissionChecker permissionChecker) {
        int cores = Runtime.getRuntime().availableProcessors();
        this.threadCount = Math.min(cores * 2, MAX_THREADS);
        this.permissionChecker = permissionChecker;
    }

    /**
     * Execute tool calls with the read-only-first ordering.
     *
     * @param toolCalls ordered list from the LLM response
     * @param registry  tool lookup
     * @return results in the same order as toolCalls
     */
    public List<ToolResult> execute(List<ToolCall> toolCalls, ToolRegistry registry) {
        // Separate READ and non-READ calls by ToolCategory, tracking original indices
        List<IndexedCall> readOnly = new ArrayList<>();
        List<IndexedCall> others = new ArrayList<>();

        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            Tool tool = registry.get(toolCall.getName());
            IndexedCall ic = new IndexedCall(i, toolCall, tool);
            if (tool != null && tool.category() == ToolCategory.READ) {
                readOnly.add(ic);
            } else {
                others.add(ic);
            }
        }

        // Results map: original index → ToolResult
        Map<Integer, ToolResult> resultMap = new LinkedHashMap<>();

        // Phase 1: concurrent READ
        if (!readOnly.isEmpty()) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (IndexedCall ic : readOnly) {
                    executor.submit(() -> {
                        ToolResult result = executeOne(ic.toolCall, ic.tool);
                        synchronized (resultMap) {
                            resultMap.put(ic.originalIndex, result);
                        }
                    });
                }
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                for (IndexedCall ic : readOnly) {
                    resultMap.putIfAbsent(ic.originalIndex,
                            new ToolResult(false, "工具执行被中断", "INTERRUPTED"));
                }
            }
        }

        // Phase 2: sequential WRITE + COMMAND
        for (IndexedCall indexedCall : others) {
            ToolResult result = executeOne(indexedCall.toolCall, indexedCall.tool);
            resultMap.put(indexedCall.originalIndex, result);
        }

        // Assemble in original order
        List<ToolResult> results = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolResult r = resultMap.get(i);
            results.add(Objects.requireNonNullElseGet(r, () -> new ToolResult(false, "工具未执行 (内部错误)", "INTERNAL_ERROR")));
        }

        return results;
    }

    private ToolResult executeOne(ToolCall toolCall, Tool tool) {
        if (tool == null) {
            return new ToolResult(false, "未知工具: " + toolCall.getName(), "UNKNOWN_TOOL");
        }

        // Permission already pre-resolved by AgentLoop.run() — see the
        // permission pre-check loop and adjustForSubAgentMode there.
        // Redundant re-checking here would break sub-agent modes like "dontAsk".

        try {
            return tool.execute(toolCall.getInput());
        } catch (Exception e) {
            return new ToolResult(false,
                    "工具执行异常: " + e.getMessage(),
                    "EXECUTION_ERROR");
        }
    }

    // ---- internal ----

    private record IndexedCall(int originalIndex, ToolCall toolCall, Tool tool) {}
}

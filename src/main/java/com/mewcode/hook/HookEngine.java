package com.mewcode.hook;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core hook engine — registers hooks, evaluates conditions, dispatches actions.
 *
 * <p>Usage:
 * <pre>{@code
 * HookEngine engine = new HookEngine(errorLogger);
 * engine.loadHooks(loadedConfigs);
 * engine.runHooks(EventName.TURN_START, HookContext.of(EventName.TURN_START));
 * PreToolResult pre = engine.runPreToolHooks("Bash", Map.of("command", "rm -rf /"));
 * }</pre>
 */
public class HookEngine {

    private final java.util.List<HookConfig> hooks = new java.util.ArrayList<>();
    private final Set<String> executedOnceIds = new HashSet<>();
    private final HookErrorLogger errorLogger;

    public HookEngine(HookErrorLogger errorLogger) {
        this.errorLogger = errorLogger;
    }

    // ---- Hook management ----

    /** Replace all hooks with a new list. */
    public void loadHooks(java.util.List<HookConfig> configs) {
        hooks.clear();
        executedOnceIds.clear();
        if (configs != null) {
            hooks.addAll(configs);
        }
    }

    /** Return the number of loaded hooks. */
    public int count() {
        return hooks.size();
    }

    // ---- Execution ----

    /**
     * Run all hooks that match the given eventName and context.
     *
     * @param eventName the triggering eventName
     * @param hookContext   the runtime context
     * @return results for every hook that was executed
     */
    public List<HookResult> runHooks(EventName eventName, HookContext hookContext) {
        java.util.List<HookResult> results = new java.util.ArrayList<>();

        for (HookConfig hookConfig : hooks) {
            if (hookConfig.event() != eventName) continue;

            // runOnce dedup
            if (hookConfig.runOnce() && executedOnceIds.contains(hookConfig.id())) {
                continue;
            }

            // Condition check
            if (hookConfig.conditionGroup() != null && !hookConfig.conditionGroup().conditions().isEmpty()) {
                boolean allMode = hookConfig.conditionGroup().isAllMode();
                if (ConditionEvaluator.evaluate(hookConfig.conditionGroup().conditions(), allMode, hookContext)) {
                    continue;
                }
            }

            // Mark runOnce as executed
            if (hookConfig.runOnce()) {
                executedOnceIds.add(hookConfig.id());
            }

            // Execute
            try {
                HookResult result = executeAction(hookConfig, hookContext);
                results.add(result);
            } catch (Exception e) {
                errorLogger.log(hookConfig.id(), "Hook execution failed: " + e.getMessage());
                results.add(new HookResult(hookConfig.id(),
                        "Hook error: " + e.getMessage(), false, hookConfig.reject()));
            }
        }

        return results;
    }

    /**
     * Run pre_tool_use hooks specifically. Returns on the first reject.
     *
     * @param toolName the tool being called
     * @param args     the tool's input parameters
     * @return a rejection result if any hook says reject; otherwise ALLOW
     */
    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
        HookContext ctx = HookContext.ofTool(EventName.PRE_TOOL_USE, toolName, args);

        for (HookConfig hook : hooks) {
            if (hook.event() != EventName.PRE_TOOL_USE) continue;

            // Condition check
            if (hook.conditionGroup() != null && !hook.conditionGroup().conditions().isEmpty()) {
                boolean allMode = hook.conditionGroup().isAllMode();
                if (ConditionEvaluator.evaluate(hook.conditionGroup().conditions(), allMode, ctx)) {
                    continue;
                }
            }

            if (!hook.reject()) {
                // Non-rejecting pre_tool_use hooks still execute (for side effects like logging)
                try {
                    executeAction(hook, ctx);
                } catch (Exception e) {
                    errorLogger.log(hook.id(), "Pre-tool hook failed: " + e.getMessage());
                }
                continue;
            }

            // Rejecting hook — execute and return
            try {
                HookResult result = executeAction(hook, ctx);
                return PreToolResult.reject(result.output() != null ? result.output() : "Hook rejected");
            } catch (Exception e) {
                errorLogger.log(hook.id(), "Reject hook failed: " + e.getMessage());
                return PreToolResult.reject("Hook error: " + e.getMessage());
            }
        }

        return PreToolResult.ALLOW;
    }

    /** Discard runOnce memory (called on session reset). */
    public void clearRunOnce() {
        executedOnceIds.clear();
    }

    // ---- Action dispatch ----

    private HookResult executeAction(HookConfig hookConfig, HookContext hookContext) {
        return switch (hookConfig.action().type()) {
            case COMMAND   -> CommandExecutor.execute(hookConfig, hookContext);
            case PROMPT    -> PromptExecutor.execute(hookConfig, hookContext);
            case HTTP      -> HttpExecutor.execute(hookConfig, hookContext);
            case SUB_AGENT -> new HookResult(hookConfig.id(),
                    "sub_agent action not yet implemented", false, hookConfig.reject());
        };
    }
}

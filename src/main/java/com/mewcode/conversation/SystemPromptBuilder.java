package com.mewcode.conversation;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the final system prompt and per-turn reminders from prioritized modules.
 *
 * <h3>Module placement</h3>
 * <ul>
 *   <li>{@link SystemPromptModule.Placement#SYSTEM} — stable content, goes into the
 *       Anthropic "system" top-level field (cache-friendly).</li>
 *   <li>{@link SystemPromptModule.Placement#REMINDER} — dynamic content, injected as a
 *       {@code <system-reminder>} user message each turn (does not break cache).</li>
 * </ul>
 *
 * <h3>Plan Mode rhythm</h3>
 * The REMINDER module named {@code "plan_mode"} receives a special injection rhythm:
 * <ul>
 *   <li>Round 1 (iteration 0): full content</li>
 *   <li>Rounds 3, 6, 9, … ((iteration + 1) % 3 == 0): full content</li>
 *   <li>Other rounds: terse content</li>
 * </ul>
 * When Plan Mode is off, the plan_mode module is skipped entirely.
 */
public class SystemPromptBuilder {

    private final List<SystemPromptModule> modules;

    /** Name of the Plan Mode module that gets rhythm-controlled injection. */
    public static final String PLAN_MODE_MODULE_NAME = "plan_mode";

    public SystemPromptBuilder(List<SystemPromptModule> modules) {
        this.modules = List.copyOf(modules);
    }

    /**
     * Compose the stable system prompt from all SYSTEM-placed modules,
     * ordered by priority (lower = earlier).
     */
    public String composeSystem() {
        return modules.stream()
                .filter(m -> m.getPlacement() == SystemPromptModule.Placement.SYSTEM)
                .sorted(Comparator.comparingInt(SystemPromptModule::getPriority))
                .map(SystemPromptModule::getContent)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Compose the per-turn reminder from all REMINDER-placed modules,
     * wrapped in {@code <system-reminder>} tags.
     *
     * @param ctx current turn context (iteration, plan mode, env info)
     */
    public String composeReminder(ReminderContext ctx) {
        String body = modules.stream()
                .filter(m -> m.getPlacement() == SystemPromptModule.Placement.REMINDER)
                .filter(m -> shouldInclude(m, ctx))
                .sorted(Comparator.comparingInt(SystemPromptModule::getPriority))
                .map(m -> resolveContent(m, ctx))
                .collect(Collectors.joining("\n\n"));

        // When deferred tools exist, add a static hint (no names — some models
        // hallucinate tool calls when they see tool names in text).
        var deferredNames = ctx.getDeferredToolNames();
        if (deferredNames != null && !deferredNames.isEmpty()) {
            String deferredLine = "\n\n#延迟工具\n"
                    + "部分 MCP 远端工具延迟加载，未直接出现在工具列表中。"
                    + "请用 ToolSearch 搜索并加载其完整定义方可调用。"
                    + "内置工具（read_file、write_file、edit_file、grep、glob、execute_command、ToolSearch）无需搜索，直接可用。";
            body = body.isBlank() ? deferredLine.strip() : body + deferredLine;
        }

        if (body.isBlank()) {
            return "";
        }

        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    private boolean shouldInclude(SystemPromptModule module, ReminderContext ctx) {
        // plan_mode module: only include when Plan Mode is active
        if (PLAN_MODE_MODULE_NAME.equals(module.getName())) {
            return ctx.isPlanMode();
        }
        return true;
    }

    private String resolveContent(SystemPromptModule module, ReminderContext ctx) {
        String raw;
        if (PLAN_MODE_MODULE_NAME.equals(module.getName())) {
            // Plan Mode rhythm: full on round 1 and every 3rd round, terse otherwise
            int round = ctx.getIteration() + 1;
            if (ctx.getIteration() == 0 || round % 3 == 0) {
                raw = module.getFullContent();
            } else {
                raw = module.getTerseContent();
            }
        } else {
            raw = module.getContent();
        }

        // Replace environment placeholders
        return raw
                .replace("{working_directory}", ctx.getWorkingDirectory() != null ? ctx.getWorkingDirectory() : "")
                .replace("{platform}", ctx.getPlatform() != null ? ctx.getPlatform() : "")
                .replace("{current_date}", ctx.getCurrentDate() != null ? ctx.getCurrentDate() : "");
    }
}

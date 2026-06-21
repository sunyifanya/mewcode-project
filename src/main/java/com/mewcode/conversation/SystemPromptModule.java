package com.mewcode.conversation;

/**
 * A named, prioritized module of system prompt content.
 *
 * Each module declares whether it belongs in the stable "system" top-level
 * field (cache-friendly) or in the per-turn "reminder" injected as a
 * {@code <system-reminder>} user message.
 */
public final class SystemPromptModule {

    private final String name;
    private final String content;
    private final int priority;
    private final Placement placement;
    /** Full version for Plan Mode (used when placement is REMINDER and module is plan_mode). */
    private final String fullContent;
    /** Terse version for Plan Mode off-cycle rounds. */
    private final String terseContent;

    public enum Placement {
        /** Stable content — goes into the Anthropic "system" top-level field. */
        SYSTEM,
        /** Dynamic content — injected via &lt;system-reminder&gt; user message each turn. */
        REMINDER
    }

    /**
     * Create a module with a single content (no full/terse variants).
     */
    public SystemPromptModule(String name, String content, int priority, Placement placement) {
        this.name = name;
        this.content = content;
        this.priority = priority;
        this.placement = placement;
        this.fullContent = content;
        this.terseContent = content;
    }

    /**
     * Create a module with full/terse variants for controlled injection rhythm.
     */
    public SystemPromptModule(String name, String content, String fullContent, String terseContent,
                              int priority, Placement placement) {
        this.name = name;
        this.content = content;
        this.priority = priority;
        this.placement = placement;
        this.fullContent = fullContent;
        this.terseContent = terseContent;
    }

    public String getName() { return name; }
    public String getContent() { return content; }
    public String getFullContent() { return fullContent; }
    public String getTerseContent() { return terseContent; }
    public int getPriority() { return priority; }
    public Placement getPlacement() { return placement; }
}

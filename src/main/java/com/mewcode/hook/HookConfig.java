package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * A single hook rule loaded from a YAML file in {@code .mewcode/hooks/}.
 *
 * <p>YAML shape (one file per hook):
 * <pre>{@code
 * id: auto-format
 * event: post_tool_use
 * if:
 *   mode: all
 *   conditions:
 *     - variable: tool
 *       operator: "=="
 *       value: WriteFile
 * action:
 *   type: command
 *   command: "mvn spotless:apply"
 *   timeout: 30
 *   background: true
 * reject: false
 * runOnce: false
 * }</pre>
 */
public record HookConfig(
        @JsonProperty("id") String id,
        @JsonProperty("event") EventName event,
        @JsonProperty("if") HookConditionGroup conditionGroup,
        @JsonProperty("action") HookAction action,
        @JsonProperty("reject") boolean reject,
        @JsonProperty("runOnce") boolean runOnce
) {
    /**
     * Compact constructor: supplies safe defaults for optional fields.
     */
    public HookConfig {
        if (conditionGroup == null) {
            conditionGroup = new HookConditionGroup("all", java.util.List.of());
        }
    }

    /** For backward compat / simpler tests. */
    public HookConfig(String id, EventName event, HookAction action) {
        this(id, event, new HookConditionGroup("all", java.util.List.of()), action, false, false);
    }

    /** True if this hook should intercept (pre_tool_use only). */
    @JsonIgnore
    public boolean isIntercepting() {
        return reject;
    }
}

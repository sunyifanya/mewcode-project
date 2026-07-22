package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The "if" block in a hook YAML — a list of conditions with a combination mode.
 */
public record HookConditionGroup(
        @JsonProperty("mode") String mode,
        @JsonProperty("conditions") List<HookCondition> conditions
) {
    /** Default for Jackson: AND mode, empty list. */
    public HookConditionGroup {
        if (mode == null || mode.isBlank()) mode = "all";
        if (conditions == null) conditions = List.of();
    }

    /** True if all conditions must match (default). */
    public boolean isAllMode() {
        return !"any".equalsIgnoreCase(mode.strip());
    }
}

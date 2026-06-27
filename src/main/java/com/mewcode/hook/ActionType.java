package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Four action types a hook can perform.
 */
public enum ActionType {
    COMMAND("command"),
    PROMPT("prompt"),
    HTTP("http"),
    SUB_AGENT("sub_agent");

    private final String value;

    ActionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ActionType fromString(String s) {
        if (s == null || s.isBlank()) return null;
        for (ActionType t : values()) {
            if (t.value.equals(s.strip().toLowerCase())) {
                return t;
            }
        }
        return null;
    }
}

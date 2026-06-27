package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 9 lifecycle events that hooks can subscribe to.
 */
public enum EventName {
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    TURN_START("turn_start"),
    TURN_END("turn_end"),
    PRE_SEND("pre_send"),
    POST_RECEIVE("post_receive"),
    PRE_TOOL_USE("pre_tool_use"),
    POST_TOOL_USE("post_tool_use"),
    SHUTDOWN("shutdown");

    private final String value;

    EventName(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EventName fromString(String s) {
        if (s == null || s.isBlank()) return null;
        for (EventName e : values()) {
            if (e.value.equals(s.strip().toLowerCase())) {
                return e;
            }
        }
        return null;
    }
}

package com.mewcode.agent;

/**
 * Maps Anthropic stop_reason strings to an enum for use in the Agent Loop.
 */
public enum StopReason {
    /** Model finished its turn naturally. */
    END_TURN("end_turn"),
    /** Model wants to call tool(s) — loop should continue. */
    TOOL_USE("tool_use"),
    /** Hit max_tokens limit — response is truncated. */
    MAX_TOKENS("max_tokens"),
    /** Hit a custom stop_sequence. */
    STOP_SEQUENCE("stop_sequence");

    private final String apiValue;

    StopReason(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    /**
     * Convert an Anthropic stop_reason string to the enum.
     *
     * @param s the raw stop_reason value from the API, may be null
     * @return the matching enum, or END_TURN if unrecognized/null
     */
    public static StopReason fromString(String s) {
        if (s == null) return END_TURN;
        for (StopReason r : values()) {
            if (r.apiValue.equals(s)) return r;
        }
        return END_TURN;
    }
}

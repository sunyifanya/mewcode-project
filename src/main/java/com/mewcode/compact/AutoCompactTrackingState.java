package com.mewcode.compact;

/**
 * State machine for the Layer 2 circuit breaker.
 *
 * Trips after {@link #MAX_CONSECUTIVE_FAILURES} consecutive auto-compact
 * failures to prevent infinite compaction loops. Manual compact (via
 * {@code /compact}) bypasses the breaker and resets it on success.
 */
public final class AutoCompactTrackingState {
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    public int consecutiveFailures;

    public boolean isTripped() {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    public void recordFailure() {
        consecutiveFailures++;
    }

    public void reset() {
        consecutiveFailures = 0;
    }
}

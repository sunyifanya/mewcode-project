package com.mewcode.permission;

/**
 * Immutable result returned by a permission check.
 */
public class PermissionResult {

    private final PermissionMode.Decision decision;
    private final String reason;
    private final String hint;
    private final String scope; // "once" / "session" / null — for human-in-the-loop

    public PermissionResult(PermissionMode.Decision decision, String reason, String hint) {
        this(decision, reason, hint, null);
    }

    public PermissionResult(PermissionMode.Decision decision, String reason, String hint, String scope) {
        this.decision = decision;
        this.reason = reason != null ? reason : "";
        this.hint = hint != null ? hint : "";
        this.scope = scope;
    }

    // -- factories --

    public static PermissionResult allow(String reason) {
        return new PermissionResult(PermissionMode.Decision.ALLOW, reason, "");
    }

    public static PermissionResult deny(String reason, String hint) {
        return new PermissionResult(PermissionMode.Decision.DENY, reason, hint);
    }

    public static PermissionResult ask(String reason, String hint) {
        return new PermissionResult(PermissionMode.Decision.ASK, reason, hint);
    }

    // -- getters --

    public PermissionMode.Decision getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getHint() { return hint; }
    public String getScope() { return scope; }

    public boolean isAllow() { return decision == PermissionMode.Decision.ALLOW; }
    public boolean isDeny()  { return decision == PermissionMode.Decision.DENY; }
    public boolean isAsk()   { return decision == PermissionMode.Decision.ASK; }
}

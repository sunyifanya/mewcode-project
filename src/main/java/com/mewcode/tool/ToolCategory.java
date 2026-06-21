package com.mewcode.tool;

/**
 * Category of a tool for the permission system to decide on.
 *
 * <p>Each tool declares its category, and {@code PermissionMode.decide()}
 * maps the (mode, category) pair to an ALLOW/DENY/ASK decision.
 */
public enum ToolCategory {
    /** Read-only tools — no side effects (read file, glob, grep). */
    READ,
    /** Mutating tools that write or edit files on disk. */
    WRITE,
    /** Shell command execution — highest risk category. */
    COMMAND
}

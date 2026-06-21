package com.mewcode.permission;

/**
 * The user's response to a permission prompt.
 *
 * <p>Sent from the UI layer back to the agent loop:
 * <ul>
 *   <li><b>ALLOW</b> — allow this one call ("once").</li>
 *   <li><b>ALLOW_ALWAYS</b> — allow this and all future matching calls
 *       in the current session ("Yes, don't ask again").</li>
 *   <li><b>DENY</b> — reject this call.</li>
 * </ul>
 */
public enum PermissionResponse {
    ALLOW,
    ALLOW_ALWAYS,
    DENY
}

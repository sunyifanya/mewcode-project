package com.mewcode.teams;

import java.util.Set;

/**
 * Coordinator mode restricts the Lead agent's tools to coordination-only.
 * When active, Lead can only use a limited set of tools focused on
 * delegating tasks, communicating with teammates, and reading code.
 *
 * <p>Four-phase workflow:
 * <ol>
 *   <li>Research: Lead explores the problem space</li>
 *   <li>Synthesis: Lead creates a plan and task decomposition</li>
 *   <li>Implementation: Lead spawns teammates to execute tasks</li>
 *   <li>Verification: Lead verifies results and resolves conflicts</li>
 * </ol>
 */
public final class Coordinator {

    private Coordinator() {}

    /** Tools permitted when coordinator mode is active. */
    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent",
            "SendMessage",
            "TaskCreate",
            "TaskGet",
            "TaskList",
            "TaskUpdate",
            "TeamCreate",
            "TeamDelete",
            "TeamStopMember",
            "TeamMerge",
            "read_file",
            "glob",
            "grep",
            "execute_command"
    );

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }
}

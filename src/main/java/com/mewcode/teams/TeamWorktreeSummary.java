package com.mewcode.teams;

import com.mewcode.worktree.AgentWorktree;
import com.mewcode.worktree.WorktreeChanges;

/**
 * Formats teammate worktree state for lead notifications and merge previews.
 */
public final class TeamWorktreeSummary {

    private TeamWorktreeSummary() {}

    public enum State { NONE, CLEAN, CHANGED, UNKNOWN }

    public record Summary(
            State state,
            String worktreePath,
            String branch,
            int changedFiles,
            int commits,
            String message
    ) {
        public String formatShort() {
            if (state == State.NONE) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("Worktree: ").append(worktreePath != null ? worktreePath : "(unknown)").append("\n");
            sb.append("Branch: ").append(branch != null ? branch : "(unknown)").append("\n");
            sb.append("State: ").append(state.name().toLowerCase());
            if (state == State.CHANGED || state == State.CLEAN) {
                sb.append(" (changedFiles=").append(changedFiles)
                        .append(", commits=").append(commits).append(")");
            }
            if (message != null && !message.isBlank()) {
                sb.append("\n").append(message);
            }
            return sb.toString();
        }
    }

    public static Summary fromMember(TeamManager.Member member) {
        if (member == null || member.worktreeResult == null) {
            return new Summary(State.NONE, null, null, 0, 0, "No teammate worktree recorded.");
        }
        return fromWorktree(member.worktreeResult);
    }

    public static Summary fromWorktree(AgentWorktree.Result result) {
        if (result == null) {
            return new Summary(State.NONE, null, null, 0, 0, "No teammate worktree recorded.");
        }
        WorktreeChanges.ChangeSummary changes = WorktreeChanges.countChanges(
                result.worktreePath(), result.headCommit());
        if (changes == null) {
            return new Summary(State.UNKNOWN, result.worktreePath(), result.worktreeBranch(),
                    -1, -1, "Unable to determine worktree state; preserving worktree.");
        }
        State state = changes.changedFiles() > 0 || changes.commits() > 0 ? State.CHANGED : State.CLEAN;
        String message = state == State.CHANGED
                ? "Teammate worktree has changes. Use TeamMerge to preview before merging."
                : "Teammate worktree is clean.";
        return new Summary(state, result.worktreePath(), result.worktreeBranch(),
                changes.changedFiles(), changes.commits(), message);
    }
}

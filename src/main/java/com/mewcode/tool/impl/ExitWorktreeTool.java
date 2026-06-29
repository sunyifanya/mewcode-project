package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import com.mewcode.worktree.WorktreeChanges;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.worktree.WorktreeSessionStore;

import java.util.ArrayList;
import java.util.Map;

/**
 * Exits a worktree session created by {@code EnterWorktree} and restores
 * the original working directory.
 * <p>
 * Supports two actions:
 * <ul>
 *   <li><b>keep</b> — leave the worktree on disk, return to original cwd</li>
 *   <li><b>remove</b> — delete the worktree (refuses if there are uncommitted
 *       changes or new commits unless {@code discard_changes: true})</li>
 * </ul>
 */
public class ExitWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;

    public ExitWorktreeTool(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    @Override
    public String getName() {
        return "ExitWorktree";
    }

    @Override
    public String getDescription() {
        return "退出由 EnterWorktree 创建的 worktree session 并恢复到原始工作目录。"
                + "action=keep 保留 worktree，action=remove 删除 worktree（有变更时需传 discard_changes:true）。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public boolean shouldDefer() {
        return true; // schema not loaded by default, discovered via ToolSearch
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", new String[]{"keep", "remove"},
                                "description", "\"keep\" 保留 worktree；\"remove\" 删除 worktree。"
                        ),
                        "discard_changes", Map.of(
                                "type", "boolean",
                                "description", "action=remove 且有变更时需设为 true 才能强制删除。"
                        )
                ),
                "required", new String[]{"action"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        var session = WorktreeSessionStore.getCurrentSession();
        if (session == null) {
            return new ToolResult(false,
                    "No-op: there is no active EnterWorktree session to exit. "
                            + "This tool only operates on worktrees created by EnterWorktree in the current session.",
                    "NO_SESSION");
        }

        String action = params.get("action") instanceof String s ? s : "keep";
        boolean discard = Boolean.TRUE.equals(params.get("discard_changes"));

        String worktreePath = session.worktreePath();
        String originalCwd = session.originalCwd();

        if ("remove".equals(action) && !discard) {
            var summary = WorktreeChanges.countChanges(worktreePath, session.originalHeadCommit());
            if (summary == null) {
                return new ToolResult(false,
                        "Could not verify worktree state. Refusing to remove without explicit confirmation. "
                                + "Re-invoke with discard_changes: true, or use action: \"keep\".",
                        "STATE_UNKNOWN");
            }
            if (summary.changedFiles() > 0 || summary.commits() > 0) {
                var parts = new ArrayList<String>();
                if (summary.changedFiles() > 0) {
                    parts.add("%d uncommitted %s".formatted(summary.changedFiles(),
                            summary.changedFiles() == 1 ? "file" : "files"));
                }
                if (summary.commits() > 0) {
                    parts.add("%d %s".formatted(summary.commits(),
                            summary.commits() == 1 ? "commit" : "commits"));
                }
                return new ToolResult(false,
                        "Worktree has %s. Removing will discard this work permanently. "
                                .formatted(String.join(" and ", parts))
                                + "Re-invoke with discard_changes: true, or use action: \"keep\".",
                        "CHANGES_DETECTED");
            }
        }

        // Clear session
        WorktreeSessionStore.restoreSession(null);
        try {
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), null);
        } catch (Exception ignored) {
            // best-effort
        }

        if ("remove".equals(action)) {
            try {
                worktreeManager.remove(session.worktreeName());
            } catch (Exception e) {
                return new ToolResult(false,
                        "Error removing worktree: " + e.getMessage(), "REMOVE_FAILED");
            }
            return new ToolResult(true,
                    "Exited and removed worktree at %s. Session is now back in %s."
                            .formatted(worktreePath, originalCwd));
        }

        return new ToolResult(true,
                "Exited worktree. Your work is preserved at %s. Session is now back in %s."
                        .formatted(worktreePath, originalCwd));
    }
}

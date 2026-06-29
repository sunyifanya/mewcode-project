package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import com.mewcode.worktree.SlugValidator;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.worktree.WorktreeSession;
import com.mewcode.worktree.WorktreeSessionStore;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * Creates an isolated git worktree and switches the session into it.
 * <p>
 * The worktree lives under {@code .mewcode/worktrees/<name>} and gets its
 * own git branch. Use {@code ExitWorktree} to leave or remove it later.
 */
public class EnterWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final String sessionId;
    private static final SecureRandom RANDOM = new SecureRandom();

    public EnterWorktreeTool(WorktreeManager worktreeManager, String sessionId) {
        this.worktreeManager = worktreeManager;
        this.sessionId = sessionId;
    }

    @Override
    public String getName() {
        return "EnterWorktree";
    }

    @Override
    public String getDescription() {
        return "创建一个隔离的 git worktree 并将当前 session 切换进去。"
                + "使用 ExitWorktree 工具在中途离开。"
                + "每个 worktree 有自己的分支和工作目录，存放在 .mewcode/worktrees/ 下。";
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
                        "name", Map.of(
                                "type", "string",
                                "description", "可选的工作区名称，最多 64 字符。"
                                        + "每段只允许字母、数字、点、下划线、连字符；"
                                        + "可用 / 做嵌套分层。不传则自动生成。"
                        )
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (WorktreeSessionStore.getCurrentSession() != null) {
            return new ToolResult(false,
                    "Already in a worktree session. Use ExitWorktree first before entering a new one.",
                    "ALREADY_IN_WORKTREE");
        }

        String slug = params.get("name") instanceof String s && !s.isBlank() ? s : null;
        if (slug == null) {
            slug = "wt-" + Integer.toHexString(RANDOM.nextInt());
        }

        try {
            SlugValidator.validate(slug);
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, e.getMessage(), "INVALID_NAME");
        }

        try {
            var info = worktreeManager.create(slug, null);

            var session = new WorktreeSession(
                    System.getProperty("user.dir"),
                    info.path(),
                    slug,
                    info.branch(),
                    "", "",
                    sessionId,
                    0
            );
            WorktreeSessionStore.restoreSession(session);
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), session);

            return new ToolResult(true,
                    "Created worktree at %s on branch %s. The session is now working in the worktree. "
                            + "Use ExitWorktree to leave mid-session."
                            .formatted(info.path(), info.branch()));
        } catch (Exception e) {
            return new ToolResult(false,
                    "Error creating worktree: " + e.getMessage(), "CREATE_FAILED");
        }
    }
}

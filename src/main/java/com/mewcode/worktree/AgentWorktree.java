package com.mewcode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Lightweight worktree API for sub-agents.
 */
public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    /**
     * Creates or resumes a worktree for a sub-agent.
     */
    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);

        Path worktreePath = Path.of(repoRoot, ".mewcode", "worktrees", SlugValidator.flatten(slug));
        String branch = SlugValidator.branchName(slug);

        // Fast-resume: check if worktree already exists
        if (Files.isDirectory(worktreePath)) {
            Files.setLastModifiedTime(worktreePath, FileTime.from(Instant.now()));
            String head = readHead(worktreePath.toString());
            return new Result(worktreePath.toString(), branch, head != null ? head : "", repoRoot);
        }

        Files.createDirectories(worktreePath.getParent());

        ProcessBuilder processBuilder = new ProcessBuilder("git", "worktree", "add", "-B", branch,
                worktreePath.toString(), "HEAD");
        processBuilder.directory(Path.of(repoRoot).toFile());
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
        processBuilder.environment().put("GIT_ASKPASS", "");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IOException("Failed to create agent worktree: " + output);
        }

        PostCreationSetup.perform(repoRoot, worktreePath.toString(), symlinkDirs);

        String head = readHead(worktreePath.toString());
        return new Result(worktreePath.toString(), branch, head != null ? head : "", repoRoot);
    }

    /**
     * Removes a worktree created by {@link #create}.
     *
     * @return true if removal succeeded, false on any error (best-effort)
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            processBuilder.directory(Path.of(gitRoot).toFile());
            processBuilder.redirectErrorStream(true);
            Process proc = processBuilder.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) return false;

            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                Thread.sleep(100); // wait for git lockfile release
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", worktreeBranch);
                delBranch.directory(Path.of(gitRoot).toFile());
                delBranch.redirectErrorStream(true);
                Process branchProc = delBranch.start();
                branchProc.getInputStream().readAllBytes();
                branchProc.waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds the notice text injected into sub-agent prompts when running
     * in an isolated worktree. Tells the sub-agent to translate paths from
     * the parent's working directory.
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return """
                You are operating in an isolated git worktree at %s — same repository, same relative
                file structure, separate working copy. Paths mentioned in the task or inherited context
                refer to the parent's working directory (%s); translate them to your worktree root.
                Re-read files before editing if the parent may have modified them. Your changes stay
                in this worktree and will not affect the parent's files."""
                .formatted(worktreeCwd, parentCwd);
    }

    // ── internal ────────────────────────────────────────────────────────────

    private static String readHead(String worktreePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(Path.of(worktreePath).toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).strip();
            proc.waitFor(10, TimeUnit.SECONDS);
            return proc.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}

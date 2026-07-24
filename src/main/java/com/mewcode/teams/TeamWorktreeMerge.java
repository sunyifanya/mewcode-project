package com.mewcode.teams;

import com.mewcode.worktree.AgentWorktree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Preview and confirm merge operations for teammate worktrees.
 */
public final class TeamWorktreeMerge {

    private TeamWorktreeMerge() {}

    public record MergeResult(boolean success, String status, String message) {}

    public static MergeResult preview(TeamManager.Member member) {
        if (member == null) {
            return new MergeResult(false, "failed", "Member not found.");
        }
        AgentWorktree.Result wt = member.worktreeResult;
        if (wt == null) {
            return new MergeResult(false, "failed",
                    "Member '%s' has no recorded worktree. Start the teammate with isolation=\"worktree\" before merging."
                            .formatted(member.getName()));
        }

        TeamWorktreeSummary.Summary summary = TeamWorktreeSummary.fromWorktree(wt);
        String status = git(wt.worktreePath(), "status", "--porcelain");
        String diffStat = git(wt.worktreePath(), "diff", "--stat");
        String commitStat = git(wt.worktreePath(), "diff", "--stat", wt.headCommit() + "..HEAD");

        StringBuilder sb = new StringBuilder();
        sb.append("TeamMerge preview for member '").append(member.getName()).append("'\n");
        sb.append(summary.formatShort()).append("\n\n");
        sb.append("Status:\n").append(status == null || status.isBlank() ? "(clean)" : status.strip()).append("\n\n");
        sb.append("Uncommitted diff stat:\n").append(diffStat == null || diffStat.isBlank() ? "(none)" : diffStat.strip()).append("\n\n");
        sb.append("Committed diff stat:\n").append(commitStat == null || commitStat.isBlank() ? "(none)" : commitStat.strip()).append("\n\n");
        sb.append("No files were changed. Re-run with confirm=true to merge.");
        return new MergeResult(true, "preview", sb.toString());
    }

    public static MergeResult merge(TeamManager.Member member, String mainWorkDir) {
        if (member == null) {
            return new MergeResult(false, "failed", "Member not found.");
        }
        AgentWorktree.Result wt = member.worktreeResult;
        if (wt == null) {
            return new MergeResult(false, "failed",
                    "Member '%s' has no recorded worktree. Start the teammate with isolation=\"worktree\" before merging."
                            .formatted(member.getName()));
        }
        String mainStatus = git(mainWorkDir, "status", "--porcelain");
        if (mainStatus == null) {
            return new MergeResult(false, "blocked", "Cannot read main worktree git status; merge blocked.");
        }
        if (!mainStatus.isBlank()) {
            return new MergeResult(false, "blocked",
                    "Main worktree has uncommitted changes; merge blocked.\n" + mainStatus.strip());
        }

        TeamWorktreeSummary.Summary summary = TeamWorktreeSummary.fromWorktree(wt);
        if (summary.state() == TeamWorktreeSummary.State.CLEAN) {
            return new MergeResult(true, "clean", "Teammate worktree has no changes to merge.\n" + summary.formatShort());
        }
        if (summary.state() == TeamWorktreeSummary.State.UNKNOWN) {
            return new MergeResult(false, "blocked", "Cannot safely determine teammate worktree changes; merge blocked.\n"
                    + summary.formatShort());
        }

        StringBuilder output = new StringBuilder();
        String revCountOut = git(wt.worktreePath(), "rev-list", "--count", wt.headCommit() + "..HEAD");
        int commits = parseInt(revCountOut);
        if (commits > 0) {
            CommandResult cherry = runGit(mainWorkDir, "cherry-pick", wt.headCommit() + ".." + wt.worktreeBranch());
            output.append(cherry.output());
            if (!cherry.success()) {
                return new MergeResult(false, "conflict",
                        "Commit merge failed or conflicted. Resolve manually; teammate worktree preserved.\n" + output);
            }
        }

        String statusOut = git(wt.worktreePath(), "status", "--porcelain");
        if (statusOut != null && !statusOut.isBlank()) {
            CommandResult apply = applyUncommittedPatch(wt.worktreePath(), mainWorkDir);
            output.append(apply.output());
            if (!apply.success()) {
                return new MergeResult(false, "conflict",
                        "Patch apply failed or conflicted. Resolve manually; teammate worktree preserved.\n" + output);
            }
        }

        TeamWorktreeSummary.Summary after = TeamWorktreeSummary.fromWorktree(wt);
        return new MergeResult(true, "merged",
                "Merged teammate worktree into main worktree. Teammate worktree preserved.\n"
                        + after.formatShort() + "\n\n" + output);
    }

    private static CommandResult applyUncommittedPatch(String worktreePath, String mainWorkDir) {
        CommandResult intentToAdd = runGit(worktreePath, "add", "-N", ".");
        if (!intentToAdd.success()) return intentToAdd;
        CommandResult diff = runGit(worktreePath, "diff", "--binary", "HEAD");
        if (!diff.success()) return diff;
        if (diff.output().isBlank()) return new CommandResult(true, "No uncommitted patch to apply.\n");
        return runProcess(mainWorkDir, diff.output(), "git", "apply", "--3way");
    }

    private static String git(String cwd, String... args) {
        CommandResult result = runGit(cwd, args);
        return result.success() ? result.output() : null;
    }

    private static CommandResult runGit(String cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return runProcess(cwd, null, command.toArray(String[]::new));
    }

    private static CommandResult runProcess(String cwd, String stdin, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(Path.of(cwd).toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes());
            }
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, output + "\n[git command timed out]");
            }
            return new CommandResult(process.exitValue() == 0, output);
        } catch (IOException e) {
            return new CommandResult(false, "Failed to execute git command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "Git command interrupted");
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record CommandResult(boolean success, String output) {}
}

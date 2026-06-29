package com.mewcode.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists {@link WorktreeSession} to disk and manages the global singleton.
 * <p>
 * The current session is a process-wide singleton. Only one worktree session
 * can be active at a time (enforced by {@code EnterWorktreeTool}).
 */
public final class WorktreeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile WorktreeSession currentSession;

    private WorktreeSessionStore() {}

    /**
     * Returns the currently active worktree session, or null.
     */
    public static WorktreeSession getCurrentSession() {
        return currentSession;
    }

    /**
     * Restore (or clear) the in-memory session singleton.
     */
    public static void restoreSession(WorktreeSession session) {
        currentSession = session;
    }

    /**
     * Persist the session to {@code .mewcode/worktree_session.json}.
     * Pass {@code null} to delete the file.
     */
    public static void save(String repoRoot, WorktreeSession session) throws IOException {
        Path path = sessionPath(repoRoot);
        if (session == null) {
            Files.deleteIfExists(path);
            return;
        }
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), session);
    }

    /**
     * Load a previously persisted session from disk.
     * Returns null if the file doesn't exist or is unreadable.
     */
    public static WorktreeSession load(String repoRoot) {
        Path path = sessionPath(repoRoot);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), WorktreeSession.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Clear the in-memory singleton (for test teardown only).
     */
    static void clearForTesting() {
        currentSession = null;
    }

    private static Path sessionPath(String repoRoot) {
        return Path.of(repoRoot, ".mewcode", "worktree_session.json");
    }
}

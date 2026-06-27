package com.mewcode.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cleans up expired session files on startup.
 *
 * <p>Two passes:
 * <ol>
 *   <li>Delete session JSONL files older than {@code maxAgeDays}, along with
 *       their corresponding {@code .mewcode/session_save_content/<sessionId>/} work directory
 *       (spilled tool results + replacement records).</li>
 *   <li>Clean orphaned session work directories that have no corresponding
 *       JSONL (e.g. from a crashed session or manual deletion).</li>
 * </ol>
 */
public class SessionCleanup {

    /**
     * Delete expired session JSONL files and their work directories.
     *
     * @param workDir    project working directory
     * @param maxAgeDays maximum age in days; sessions older than this are deleted
     * @return number of deleted session JSONL files
     */
    public static int cleanIfNeeded(String workDir, int maxAgeDays) {
        Path sessionsDir = Path.of(workDir, ".mewcode", "session_transcripts");
        Path sessionWorkDir = Path.of(workDir, ".mewcode", "session_save_content");
        if (!Files.isDirectory(sessionsDir)) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
        int deleted = 0;

        // Pass 1: delete expired session JSONL files + their work directories
        try (Stream<Path> paths = Files.list(sessionsDir)) {
            var files = paths.filter(p -> p.toString().endsWith(".jsonl"))
                             .filter(Files::isRegularFile)
                             .toList();

            for (Path file : files) {
                try {
                    Instant modTime = Files.getLastModifiedTime(file).toInstant();
                    if (modTime.isBefore(cutoff)) {
                        String sessionId = fileNameToId(file.getFileName().toString());
                        Files.deleteIfExists(file);
                        deleteSessionWorkDir(sessionWorkDir, sessionId);
                        deleted++;
                    }
                } catch (IOException ignored) {
                    // skip files we can't read or delete
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }

        // Pass 2: clean orphaned session work directories (no corresponding JSONL)
        if (Files.isDirectory(sessionWorkDir)) {
            Set<String> existingIds = new HashSet<>();
            try (Stream<Path> paths = Files.list(sessionsDir)) {
                paths.filter(p -> p.toString().endsWith(".jsonl"))
                     .filter(Files::isRegularFile)
                     .forEach(p -> existingIds.add(fileNameToId(p.getFileName().toString())));
            } catch (IOException ignored) {
                // best-effort
            }

            try (Stream<Path> dirs = Files.list(sessionWorkDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String dirName = dir.getFileName().toString();
                    if (!existingIds.contains(dirName)) {
                        deleteRecursively(dir);
                    }
                });
            } catch (IOException ignored) {
                // best-effort
            }
        }

        if (deleted > 0) {
            System.out.println("已清理 " + deleted + " 个过期会话");
        }
        return deleted;
    }

    /**
     * Extract session ID from a JSONL file name by stripping the {@code .jsonl} suffix.
     */
    private static String fileNameToId(String fileName) {
        return fileName.substring(0, fileName.length() - ".jsonl".length());
    }

    /**
     * Delete the session work directory for the given session ID, if it exists.
     */
    private static void deleteSessionWorkDir(Path sessionWorkDir, String sessionId) {
        Path dir = sessionWorkDir.resolve(sessionId);
        if (Files.isDirectory(dir)) {
            deleteRecursively(dir);
        }
    }

    /**
     * Recursively delete a directory and all its contents. Best-effort:
     * individual file deletion failures are silently ignored.
     */
    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}

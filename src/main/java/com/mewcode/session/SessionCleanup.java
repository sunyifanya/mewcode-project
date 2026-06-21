package com.mewcode.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

/**
 * Cleans up expired session files on startup.
 *
 * <p>Session JSONL files older than a configurable number of days
 * (default 30) are deleted. This runs once at application startup.
 */
public class SessionCleanup {

    /**
     * Delete session files older than {@code maxAgeDays}.
     *
     * @param workDir    project working directory
     * @param maxAgeDays maximum age in days; sessions older than this are deleted
     * @return number of deleted files
     */
    public static int cleanIfNeeded(String workDir, int maxAgeDays) {
        Path sessionsDir = Path.of(workDir, ".mewcode", "sessions");
        if (!Files.isDirectory(sessionsDir)) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
        int deleted = 0;

        try (Stream<Path> paths = Files.list(sessionsDir)) {
            var files = paths.filter(p -> p.toString().endsWith(".jsonl"))
                             .filter(Files::isRegularFile)
                             .toList();

            for (Path file : files) {
                try {
                    Instant modTime = Files.getLastModifiedTime(file).toInstant();
                    if (modTime.isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (IOException ignored) {
                    // skip files we can't read or delete
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }

        if (deleted > 0) {
            System.out.println("已清理 " + deleted + " 个过期会话");
        }
        return deleted;
    }
}

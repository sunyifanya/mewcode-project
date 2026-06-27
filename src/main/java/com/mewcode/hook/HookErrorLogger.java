package com.mewcode.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes hook execution failures to {@code .mewcode/hooks-errors.log}.
 *
 * <p>Format: {@code [2026-06-27T14:30:00] [hook-id] error message}
 * Falls back to {@code System.err.println} if the log file can't be written.
 */
public class HookErrorLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path logFile;

    public HookErrorLogger(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * Append an error entry.
     *
     * @param hookId  the failing hook's id
     * @param message the error description
     */
    public void log(String hookId, String message) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] [" + hookId + "] " + message;
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[HookErrorLogger fallback] " + line);
        }
    }
}

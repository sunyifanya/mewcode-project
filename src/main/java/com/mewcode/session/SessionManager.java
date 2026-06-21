package com.mewcode.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Manages session persistence as JSONL files in {@code .mewcode/sessions/}.
 *
 * <p>Each session is one JSONL file named {@code yyyyMMdd-HHmmss-xxxx.jsonl}.
 * No separate meta file — listing scans JSONL files directly.
 */
public class SessionManager {

    public record SessionMessage(String role, String content, long timestamp) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".mewcode", "sessions");
    }

    // ---- ID generation ----

    /**
     * Generate a session ID with sub-second collision avoidance.
     * Format: {@code yyyyMMdd-HHmmss-xxxx} where xxxx is 4 random hex digits.
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int random = ThreadLocalRandom.current().nextInt(0x10000);
        return String.format("%s-%04x", timestamp, random);
    }

    // ---- Persistence ----

    /**
     * Append one message line to the session JSONL file.
     *
     * @param workDir   project working directory
     * @param sessionId session identifier
     * @param role      message role (user, assistant, system, tool_use, tool_result)
     * @param content   message content
     */
    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());

            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort persistence
        }
    }

    /**
     * Load all messages from a session JSONL file.
     * Malformed lines are silently skipped.
     *
     * @param workDir   project working directory
     * @param sessionId session identifier
     * @return list of session messages (may be empty)
     */
    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    if (role != null && content != null && !content.isEmpty()) {
                        messages.add(new SessionMessage(role, content, ts));
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    // ---- Listing ----

    /**
     * List all sessions in the project, sorted by modification time (newest first).
     *
     * @param workDir project working directory
     * @return list of session info, may be empty
     */
    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         // Read the first user message as the session title
                         String first = extractFirstUserMessage(p);
                         int msgCount = countValidMessages(p);
                         sessions.add(new SessionInfo(id, first, msgCount, fileSize, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    /**
     * Extract the first user message from a JSONL file as a short title.
     */
    private static String extractFirstUserMessage(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    if ("user".equals(map.get("role"))) {
                        String content = (String) map.get("content");
                        if (content != null && !content.isEmpty()) {
                            // Truncate to 80 chars for display
                            return content.length() > 80 ? content.substring(0, 80) + "..." : content;
                        }
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return "";
    }

    /**
     * Count valid (non-blank, parseable) lines in a JSONL file.
     */
    private static int countValidMessages(Path file) {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    MAPPER.readTree(line);
                    count++;
                } catch (IOException ignored) {
                    // skip malformed
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return count;
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        java.time.Duration d = java.time.Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 7) {
            return days + " 天前";
        }
        long weeks = days / 7;
        return weeks + " 周前";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }
}

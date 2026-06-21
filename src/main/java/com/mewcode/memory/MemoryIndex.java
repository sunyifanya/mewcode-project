package com.mewcode.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads, writes, and validates the {@code MEMORY.md} index file.
 *
 * <p>Format: one line per memory entry:
 * <pre>
 * - [Title](file.md) — hook
 * </pre>
 *
 * <p>Enforces a hard cap of 200 lines / 25 KB. When exceeded,
 * trims to the 200 most recently modified entries.
 */
public class MemoryIndex {

    static final int MAX_LINES = 200;
    static final int MAX_SIZE_BYTES = 25_000; // ~25KB

    /**
     * Represents one line in the index.
     */
    public record IndexLine(String title, String fileName, String hook) {}

    /**
     * Parse the MEMORY.md index file.
     *
     * @param dir memory directory
     * @return list of index lines (empty if file doesn't exist)
     */
    public static List<IndexLine> read(Path dir) {
        Path indexFile = dir.resolve("MEMORY.md");
        if (!Files.exists(indexFile)) {
            return new ArrayList<>();
        }

        List<IndexLine> lines = new ArrayList<>();
        try {
            List<String> fileLines = Files.readAllLines(indexFile);
            for (String line : fileLines) {
                IndexLine parsed = parseLine(line);
                if (parsed != null) {
                    lines.add(parsed);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return lines;
    }

    /**
     * Write the index file, enforcing size limits.
     *
     * @param dir   memory directory
     * @param lines the full list of index entries
     */
    public static void write(Path dir, List<IndexLine> lines) {
        try {
            Files.createDirectories(dir);
            Path indexFile = dir.resolve("MEMORY.md");

            // Enforce size limits
            List<IndexLine> trimmed = lines;
            if (trimmed.size() > MAX_LINES) {
                // Sort by file modification time (newest first), keep top MAX_LINES
                trimmed = trimByModTime(dir, trimmed, MAX_LINES);
            }

            StringBuilder sb = new StringBuilder();
            for (IndexLine line : trimmed) {
                sb.append("- [").append(line.title()).append("](")
                  .append(line.fileName()).append(")");
                if (line.hook() != null && !line.hook().isEmpty()) {
                    sb.append(" — ").append(line.hook());
                }
                sb.append('\n');
            }

            String content = sb.toString();
            // If still over size limit, trim further
            while (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SIZE_BYTES
                    && trimmed.size() > 10) {
                trimmed = trimmed.subList(0, trimmed.size() - 10);
                sb = new StringBuilder();
                for (IndexLine line : trimmed) {
                    sb.append("- [").append(line.title()).append("](")
                      .append(line.fileName()).append(")\n");
                }
                content = sb.toString();
            }

            Files.writeString(indexFile, content);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Add a single entry to the index, removing any existing entry with the same filename.
     */
    public static List<IndexLine> upsert(List<IndexLine> lines, IndexLine newLine) {
        List<IndexLine> result = new ArrayList<>();
        boolean replaced = false;
        for (IndexLine line : lines) {
            if (line.fileName().equals(newLine.fileName())) {
                result.add(newLine);
                replaced = true;
            } else {
                result.add(line);
            }
        }
        if (!replaced) {
            result.add(newLine);
        }
        return result;
    }

    /**
     * Remove an entry from the index by filename.
     */
    public static List<IndexLine> remove(List<IndexLine> lines, String fileName) {
        return lines.stream()
                .filter(l -> !l.fileName().equals(fileName))
                .toList();
    }

    /**
     * Parse one line of the index file.
     * Format: "- [Title](file.md) — hook"
     */
    private static IndexLine parseLine(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("- [")) return null;

        int bracketEnd = trimmed.indexOf("](");
        if (bracketEnd < 3) return null;

        String title = trimmed.substring(3, bracketEnd);

        int parenEnd = trimmed.indexOf(')', bracketEnd);
        if (parenEnd < 0) return null;

        String fileName = trimmed.substring(bracketEnd + 2, parenEnd);

        String hook = "";
        int dashIdx = trimmed.indexOf(" — ", parenEnd);
        if (dashIdx > 0) {
            hook = trimmed.substring(dashIdx + 3).trim();
        }

        return new IndexLine(title, fileName, hook);
    }

    /**
     * Sort by file modification time (newest first) and keep the top N.
     */
    private static List<IndexLine> trimByModTime(Path dir, List<IndexLine> lines, int max) {
        return lines.stream()
                .sorted(Comparator.comparingLong((IndexLine l) -> {
                    try {
                        return Files.getLastModifiedTime(dir.resolve(l.fileName())).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }).reversed())
                .limit(max)
                .toList();
    }
}

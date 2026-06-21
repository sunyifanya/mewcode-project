package com.mewcode.memory;

import com.mewcode.conversation.ConversationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Manages persistent memory across sessions.
 *
 * <p>Memories are stored as individual Markdown files with YAML frontmatter
 * in two directories:
 * <ul>
 *   <li>{@code ~/.mewcode/memory/} — user-scoped (user, feedback types)</li>
 *   <li>{@code {project}/.mewcode/memory/} — project-scoped (project, reference types)</li>
 * </ul>
 *
 * <p>An index file {@code MEMORY.md} in each directory provides
 * a quick overview that is injected into the conversation context.
 */
public class MemoryManager {

    private static final String MEMORY_DIR = ".mewcode/memory";
    static final Set<String> USER_TYPES = Set.of("user", "feedback");
    static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final Path userMemoryDir;
    private final Path projectMemoryDir;
    private final int extractionInterval;
    private int turnCount;

    /**
     * Create a MemoryManager for the given project directory.
     *
     * @param workDir            project working directory
     * @param extractionInterval how many turns between extraction attempts
     */
    public MemoryManager(String workDir, int extractionInterval) {
        this.userMemoryDir = Path.of(System.getProperty("user.home", "."), MEMORY_DIR);
        this.projectMemoryDir = Path.of(workDir, MEMORY_DIR);
        this.extractionInterval = extractionInterval;
        this.turnCount = 0;
    }

    /**
     * Create with default extraction interval of 5.
     */
    public MemoryManager(String workDir) {
        this(workDir, 5);
    }

    // ---- Directories ----

    public Path getUserMemoryDir() { return userMemoryDir; }
    public Path getProjectMemoryDir() { return projectMemoryDir; }

    // ---- Index management ----

    /**
     * Load the combined memory index content for injection into the context.
     * Merges both project and user memory index files.
     */
    public String getMemoryIndexContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto Memory\n\n");

        List<MemoryIndex.IndexLine> projectLines = MemoryIndex.read(projectMemoryDir);
        List<MemoryIndex.IndexLine> userLines = MemoryIndex.read(userMemoryDir);

        if (!projectLines.isEmpty()) {
            sb.append("## Project Memory\n");
            for (MemoryIndex.IndexLine line : projectLines) {
                sb.append("- [").append(line.title()).append("](")
                  .append(line.fileName()).append(")");
                if (line.hook() != null && !line.hook().isEmpty()) {
                    sb.append(" — ").append(line.hook());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!userLines.isEmpty()) {
            sb.append("## User Memory\n");
            for (MemoryIndex.IndexLine line : userLines) {
                sb.append("- [").append(line.title()).append("](")
                  .append(line.fileName()).append(")");
                if (line.hook() != null && !line.hook().isEmpty()) {
                    sb.append(" — ").append(line.hook());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (projectLines.isEmpty() && userLines.isEmpty()) {
            return "";
        }
        return sb.toString();
    }

    /**
     * Get all memory titles from both directories for quick context injection.
     */
    public String getAllMemoryTitles() {
        return getMemoryIndexContent();
    }

    // ---- CRUD ----

    /**
     * Upsert a memory entry (create or update if slug matches and content is similar).
     *
     * @param entry the memory entry to save
     * @return true if a new file was created, false if it was skipped as duplicate
     */
    public boolean upsertEntry(MemoryEntry entry) {
        Path dir = getDirForType(entry.type());
        Path filePath = dir.resolve(entry.name() + ".md");

        // Check if an existing entry with the same slug already exists
        if (Files.exists(filePath)) {
            try {
                String existing = Files.readString(filePath);
                MemoryEntry existingEntry = MemoryEntry.fromMarkdown(existing);
                if (existingEntry != null) {
                    double overlap = MemoryEntry.overlapRatio(
                            existingEntry.content(), entry.content());
                    if (overlap > 0.80) {
                        // Too similar — skip
                        return false;
                    }
                }
            } catch (IOException ignored) {
                // if we can't read, overwrite
            }
        }

        try {
            Files.createDirectories(dir);
            String markdown = entry.toMarkdown();
            Files.writeString(filePath, markdown, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // Update the index
            List<MemoryIndex.IndexLine> lines = MemoryIndex.read(dir);
            String title = entry.description() != null && !entry.description().isEmpty()
                    ? entry.description()
                    : entry.name();
            lines = MemoryIndex.upsert(lines,
                    new MemoryIndex.IndexLine(title, entry.name() + ".md", null));
            MemoryIndex.write(dir, lines);

            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Upsert multiple entries at once, writing the index only once.
     */
    public int upsertEntries(List<MemoryEntry> entries) {
        int count = 0;
        for (MemoryEntry entry : entries) {
            // Use individual upsertEntry to check duplicates properly
            // but batch the writes per directory
            Path dir = getDirForType(entry.type());
            Path filePath = dir.resolve(entry.name() + ".md");

            if (Files.exists(filePath)) {
                try {
                    String existing = Files.readString(filePath);
                    MemoryEntry existingEntry = MemoryEntry.fromMarkdown(existing);
                    if (existingEntry != null) {
                        double overlap = MemoryEntry.overlapRatio(
                                existingEntry.content(), entry.content());
                        if (overlap > 0.80) continue;
                    }
                } catch (IOException ignored) {}
            }

            try {
                Files.createDirectories(dir);
                Files.writeString(filePath, entry.toMarkdown(), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                count++;
            } catch (IOException ignored) {}
        }

        // Refresh both indexes
        refreshIndex(projectMemoryDir);
        refreshIndex(userMemoryDir);

        return count;
    }

    /**
     * Delete a memory entry by slug and type.
     */
    public boolean deleteEntry(String slug, String type) {
        Path dir = getDirForType(type);
        Path filePath = dir.resolve(slug + ".md");
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                List<MemoryIndex.IndexLine> lines = MemoryIndex.read(dir);
                lines = MemoryIndex.remove(lines, slug + ".md");
                MemoryIndex.write(dir, lines);
            }
            return deleted;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * List all memory entries (both project and user scoped).
     */
    public List<MemoryEntry> listAll() {
        List<MemoryEntry> all = new ArrayList<>();
        try {
            if (Files.isDirectory(projectMemoryDir)) {
                try (var files = Files.list(projectMemoryDir)) {
                    files.filter(f -> f.toString().endsWith(".md")
                                    && !f.getFileName().toString().equals("MEMORY.md"))
                         .forEach(f -> {
                             try {
                                 String content = Files.readString(f);
                                 MemoryEntry entry = MemoryEntry.fromMarkdown(content);
                                 if (entry != null) all.add(entry);
                             } catch (IOException ignored) {}
                         });
                }
            }
            if (Files.isDirectory(userMemoryDir)) {
                try (var files = Files.list(userMemoryDir)) {
                    files.filter(f -> f.toString().endsWith(".md")
                                    && !f.getFileName().toString().equals("MEMORY.md"))
                         .forEach(f -> {
                             try {
                                 String content = Files.readString(f);
                                 MemoryEntry entry = MemoryEntry.fromMarkdown(content);
                                 if (entry != null) all.add(entry);
                             } catch (IOException ignored) {}
                         });
                }
            }
        } catch (IOException ignored) {}
        return all;
    }

    // ---- Extraction trigger ----

    /**
     * Check whether it's time to run memory extraction.
     * Increments the internal turn counter and returns true
     * every {@code extractionInterval} turns.
     */
    public boolean shouldExtract() {
        turnCount++;
        return turnCount % extractionInterval == 0;
    }

    /**
     * Reset the turn counter (e.g., on conversation clear).
     */
    public void resetTurnCount() {
        turnCount = 0;
    }

    public int getTurnCount() { return turnCount; }

    // ---- Helpers ----

    private Path getDirForType(String type) {
        if (type != null && USER_TYPES.contains(type)) {
            return userMemoryDir;
        }
        // project, reference, legacy without type → project dir
        return projectMemoryDir;
    }

    /**
     * Rebuild the index for a directory by scanning all .md files.
     */
    private void refreshIndex(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return;
            List<MemoryIndex.IndexLine> lines = new ArrayList<>();
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md")
                                && !f.getFileName().toString().equals("MEMORY.md"))
                     .sorted()
                     .forEach(f -> {
                         try {
                             String content = Files.readString(f);
                             MemoryEntry entry = MemoryEntry.fromMarkdown(content);
                             if (entry != null) {
                                 String title = entry.description() != null
                                         && !entry.description().isEmpty()
                                         ? entry.description()
                                         : entry.name();
                                 lines.add(new MemoryIndex.IndexLine(
                                         title, entry.name() + ".md", null));
                             }
                         } catch (IOException ignored) {}
                     });
            }
            MemoryIndex.write(dir, lines);
        } catch (IOException ignored) {}
    }
}

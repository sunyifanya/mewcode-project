package com.mewcode.memory;

import java.time.Instant;

/**
 * A single memory entry with YAML frontmatter.
 *
 * <p>Each memory is stored as a Markdown file with frontmatter:
 * <pre>
 * ---
 * name: short-kebab-case-slug
 * description: one-line summary
 * metadata:
 *   type: user | feedback | project | reference
 * ---
 *
 * Content body...
 * </pre>
 *
 * @param name        unique slug (kebab-case)
 * @param description one-line summary
 * @param type        memory type (user/feedback/project/reference)
 * @param content     full markdown content (body)
 * @param timestamp   ISO instant string of creation/last update
 */
public record MemoryEntry(
        String name,
        String description,
        String type,
        String content,
        String timestamp
) {
    /**
     * Create a new entry with the current time.
     */
    public MemoryEntry(String name, String description, String type, String content) {
        this(name, description, type, content, Instant.now().toString());
    }

    /**
     * Serialize the entry as a frontmatter Markdown file.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escapeYaml(name)).append("\n");
        sb.append("description: ").append(escapeYaml(description)).append("\n");
        sb.append("metadata:\n");
        sb.append("  type: ").append(escapeYaml(type)).append("\n");
        sb.append("---\n\n");
        sb.append(content);
        if (!content.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Parse a frontmatter Markdown file into a MemoryEntry.
     *
     * @param markdown the full file content
     * @return parsed entry, or null if frontmatter is missing or invalid
     */
    public static MemoryEntry fromMarkdown(String markdown) {
        if (markdown == null || !markdown.startsWith("---\n")) {
            return null;
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            // Try alternative: "---\r\n" or end of frontmatter
            end = markdown.indexOf("\n---", 4);
            if (end < 0) return null;
        }

        String frontmatter = markdown.substring(4, end);
        String body = markdown.substring(end + (markdown.charAt(end + 4) == '\n' ? 5 : 4));

        String name = null, description = null, type = null;
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        boolean inMetadata = false;

        for (String line : frontmatter.split("\n")) {
            boolean indented = line.startsWith("  ") || line.startsWith("\t");

            // Try to parse as a key:value first (even if indented)
            String strippedLine = line.strip();
            int colonIdx = strippedLine.indexOf(':');

            if (colonIdx > 0) {
                // This line contains a key: value pair
                // Flush previous key first
                if (currentKey != null) {
                    String value = currentValue.toString().trim();
                    switch (currentKey) {
                        case "name" -> name = value;
                        case "description" -> description = value;
                        case "metadata.type" -> type = value;
                    }
                }

                String key = strippedLine.substring(0, colonIdx).trim();
                String value = strippedLine.substring(colonIdx + 1).trim();

                if ("metadata".equals(key)) {
                    inMetadata = true;
                    currentKey = null;
                } else if (inMetadata && indented) {
                    currentKey = "metadata." + key;
                    currentValue = new StringBuilder(value);
                } else {
                    inMetadata = false;
                    currentKey = key;
                    currentValue = new StringBuilder(value);
                }
            } else if (indented && currentKey != null) {
                // Continuation of a multi-line value
                currentValue.append("\n").append(line.strip());
            }
        }
        // Flush last key
        if (currentKey != null) {
            String value = currentValue.toString().trim();
            switch (currentKey) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "metadata.type" -> type = value;
            }
        }

        if (name == null || type == null) {
            return null;
        }
        return new MemoryEntry(name, description != null ? description : "", type, body.trim());
    }

    /**
     * Generate a slug from a text string.
     * Takes first ~8 words, lowercases, replaces non-alphanumeric with dashes.
     */
    public static String slugFromText(String text) {
        if (text == null || text.isBlank()) return "memory";
        String[] words = text.trim().split("\\s+");
        StringBuilder slug = new StringBuilder();
        int wordCount = 0;
        for (String word : words) {
            if (wordCount >= 8) break;
            String cleaned = word.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff-]", "");
            if (!cleaned.isEmpty()) {
                if (!slug.isEmpty()) slug.append('-');
                slug.append(cleaned);
                wordCount++;
            }
        }
        return slug.length() > 100 ? slug.substring(0, 100) : slug.toString();
    }

    /**
     * Simple text overlap ratio between two strings.
     * Uses word-level Jaccard-like overlap after normalization.
     */
    public static double overlapRatio(String a, String b) {
        if (a == null || b == null) return 0.0;
        String na = a.toLowerCase().replaceAll("\\s+", " ").trim();
        String nb = b.toLowerCase().replaceAll("\\s+", " ").trim();
        if (na.isEmpty() || nb.isEmpty()) return 0.0;

        // Use character-level overlap for simplicity
        int shorter = Math.min(na.length(), nb.length());
        int matches = 0;
        for (int i = 0; i < shorter; i++) {
            if (na.charAt(i) == nb.charAt(i)) matches++;
        }
        return (double) matches / Math.max(na.length(), nb.length());
    }

    private static String escapeYaml(String s) {
        if (s == null) return "";
        if (s.contains(":") || s.contains("\"") || s.contains("'")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}

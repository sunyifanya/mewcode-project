package com.mewcode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads permission rules from three YAML sources, stacked in order:
 * <ol>
 *   <li>{@code ~/.mewcode/permissions.yaml} — user-level (lowest priority)</li>
 *   <li>{@code <project>/.mewcode/permissions.yaml} — project-level (shared via git, highest)</li>
 * </ol>
 *
 * <p>Rules from all files are combined into one list. Later files' rules
 * appear later in the list, and the RuleEngine iterates in reverse so
 * last-listed rules win — giving local overrides the highest priority.
 *
 * <p>YAML format:
 * <pre>{@code
 * rules:
 *   - rule: "Bash(git *)"
 *     effect: allow
 *   - rule: "WriteFile(/etc/**)"
 *     effect: deny
 * }</pre>
 */
public class PermissionConfig {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    // ---- Public API ----

    /**
     * Load rules from all three sources stacked.
     *
     * @param projectRoot absolute path to the project root (where mewcode.yaml lives)
     */
    public static List<RuleEntry> load(Path projectRoot) {
        List<RuleEntry> all = new ArrayList<>();

        // 1. User-level (lowest priority)
        String home = System.getProperty("user.home");
        if (home != null) {
            Path userFile = Path.of(home, ".mewcode", "permissions.yaml");
            all.addAll(loadFile(userFile));
        }

        // 2. Project-level (shared)
        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".mewcode").resolve("permissions.yaml");
            all.addAll(loadFile(projectFile));
        }

        return all;
    }

    /**
     * Backward-compatible: load only from the user-level file.
     * Used when no project root is available.
     */
    public static List<RuleEntry> load() {
        return load(null);
    }

    // ---- File I/O ----

    static List<RuleEntry> loadFile(Path filePath) {
        File file = filePath.toFile();
        if (!file.exists()) {
            return List.of();
        }

        PermissionFile permFile;
        try {
            permFile = YAML_MAPPER.readValue(file, PermissionFile.class);
        } catch (Exception e) {
            System.err.println("警告: 权限配置文件格式错误 (" + filePath + "): " + e.getMessage());
            return List.of();
        }

        if (permFile == null || permFile.rules == null) {
            return List.of();
        }

        List<RuleEntry> valid = new ArrayList<>();
        for (RuleEntry entry : permFile.rules) {
            entry.resolve();
            if (entry.getToolName() == null || entry.getToolName().isBlank()) {
                System.err.println("警告: 跳过格式错误的规则 (文件: " + filePath + ")");
                continue;
            }
            if (entry.getPattern() == null || entry.getPattern().isBlank()) {
                System.err.println("警告: 跳过缺少 pattern 的规则 '" + entry.getToolName() + "'");
                continue;
            }
            valid.add(entry);
        }
        return valid;
    }

    // ---- YAML wrapper ----

    private static class PermissionFile {
        @com.fasterxml.jackson.annotation.JsonProperty("rules")
        List<RuleEntry> rules;
    }
}

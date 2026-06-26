package com.mewcode.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages skill discovery, loading, and context generation.
 */
public class SkillCatalog {

    // ── Data types ──────────────────────────────────────────────────────

    public record SkillMeta(
            String name,
            String description,
            List<String> allowedTools,
            String mode,
            String forkContext,
            String model
    ) {}

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {
        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir, true);
        }
    }

    // ── State ───────────────────────────────────────────────────────────

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private String workDir;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // ── Public API ──────────────────────────────────────────────────────

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public void register(Skill skill) {
        register(skill, "");
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Returns the skill with its body loaded. For disk-backed skills the
     * body is re-read on every call (hot reload). On read failure the
     * previously-cached body is preserved.
     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // Keep the previously-cached body
        }
        return Optional.of(skill);
    }

    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    // ── Two-tier catalog loading ──────────────────────────────────────

    /**
     * Builds a catalog by merging two tiers, with later sources
     * overriding earlier ones by name (project wins over user).
     * Phase-1: only frontmatter is read; bodies stay empty until
     * {@link #getFull} is called.
     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog skillcatalog = new SkillCatalog();
        skillcatalog.workDir = workDir;

        // Tier 1: user global
        String home = System.getProperty("user.home");
        if (home != null) {
            skillcatalog.loadTier(Path.of(home, ".mewcode", "skills"), "user");
        }

        // Tier 2: project (.mewcode/skills/ and skills/)
        skillcatalog.loadTier(Path.of(workDir, ".mewcode", "skills"), "project");
        skillcatalog.loadTier(Path.of(workDir, "skills"), "project");

        return skillcatalog;
    }

    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
    }

    /**
     * Walk {@code dir}; each immediate subdirectory is treated as a skill.
     * Missing or inaccessible directories are silently ignored.
     */
    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir.toString());
    }

    // ── Context building ───────────────────────────────────────────────

    /**
     * Build a list of available skills for Phase 1 injection (name + description only).
     */
    public String buildAvailableSkillsList() {
        if (skills.isEmpty()) {
            return "No skills installed.\n\nAdd skills to .mewcode/skills/<skill-name>/SKILL.md";
        }
        var sb = new StringBuilder();
        sb.append("The following skills are available. Use the Skill tool to load one when you need specialized instructions for a task.\n\n");
        for (var entry : skills.entrySet()) {
            var meta = entry.getValue().meta();
            sb.append("- ").append(meta.name()).append(": ").append(meta.description()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a context block suitable for system-prompt injection that
     * contains the prompt bodies of the given active skill names.
     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        // Sort by name for deterministic output
        var sorted = activeSkillNames.stream().sorted().toList();
        for (var name : sorted) {
            var skill = skills.get(name);
            if (skill != null && skill.promptBody() != null && !skill.promptBody().isEmpty()) {
                sb.append("### ").append(name).append("\n");
                sb.append(skill.promptBody()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── Loading internals ───────────────────────────────────────────────

    private void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                    // Skip individual broken skills; log to stderr
                    System.err.println("[skills] skip: failed to load " + skillDir + " — " + ignored.getMessage());
                }
            });
        } catch (IOException ignored) {
            // Directory list failed
        }
    }

    /**
     * Load a single skill from a directory. Returns null if no valid
     * skill definition is found (no SKILL.md, no skill.yaml).
     */
    private static Skill loadSkill(Path dir) throws IOException {
        // Strategy 1: skill.yaml + prompt.md
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }

        // Strategy 2: SKILL.md with optional YAML front-matter
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            String content = Files.readString(mdPath);
            if (content.isBlank()) {
                System.err.println("[skills] skip: empty file " + mdPath);
                return null;
            }
            return parseSkillMD(dir, content);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        String yamlText = Files.readString(metaPath);
        Map<String, Object> map;
        try {
            map = YAML.readValue(yamlText, Map.class);
        } catch (Exception e) {
            System.err.println("[skills] skip: YAML parse error in " + metaPath + " — " + e.getMessage());
            return null;
        }
        if (map == null) {
            map = Map.of();
        }

        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }

        return new Skill(meta, promptBody, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(Path dir, String content) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Map<String, Object> parsed = YAML.readValue(yamlBlock, Map.class);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception e) {
                    System.err.println("[skills] skip: YAML frontmatter parse error in " + dir.resolve("SKILL.md") + " — " + e.getMessage());
                    return null;
                }
            }
            // If no closing ---, treat entire file as body (no frontmatter)
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // Auto-generate description from first non-empty, non-heading line if absent
        String description = meta.description();
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), description != null ? description : "",
                    meta.allowedTools(), meta.mode(), meta.forkContext(), meta.model());
        }

        return new Skill(meta, body, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString().toLowerCase().replace(' ', '-');
        }

        String description = stringVal(map, "description");
        if (description == null) description = "";

        List<String> allowedTools = List.of();
        Object rawAllowed = map.get("allowed_tools");
        if (rawAllowed instanceof List<?> list) {
            allowedTools = list.stream().map(Object::toString).toList();
        } else if (rawAllowed instanceof String s) {
            // Gracefully handle a single string instead of a list
            allowedTools = List.of(s);
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            mode = "inline";
        }

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        String model = stringVal(map, "model");
        if (model == null) model = "";

        return new SkillMeta(name, description, allowedTools, mode, forkContext, model);
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}

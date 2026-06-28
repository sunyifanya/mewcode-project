package com.mewcode.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads sub-agent definitions from three sources (in priority order):
 * <ol>
 *   <li>Built-in specs from classpath {@code subagent/builtin/*.md}</li>
 *   <li>User-level definitions from {@code ~/.mewcode/agents/*.md}</li>
 *   <li>Project-level definitions from {@code <projectRoot>/.mewcode/agents/*.md}</li>
 * </ol>
 * Later sources override earlier ones with the same agent name.
 *
 * <p>Each {@code .md} file uses optional YAML frontmatter delimited by {@code ---}
 * followed by a Markdown body that becomes the system prompt override.</p>
 */
public final class AgentLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private AgentLoader() {}

    /**
     * Loads all agent definitions: built-in, user-level, then project-level.
     *
     * @param projectRoot the project root directory (may be null to skip project-level)
     * @return a catalog with all loaded definitions
     */
    public static AgentCatalog loadAll(Path projectRoot) {
        AgentCatalog catalog = new AgentCatalog();

        // Layer 1: Built-in (classpath)
        loadBuiltins(catalog);

        // Layer 2: User-level (~/.mewcode/agents/)
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            loadDir(catalog, Path.of(home, ".mewcode", "agents"), "user");
        }

        // Layer 3: Project-level (<root>/.mewcode/agents/)
        if (projectRoot != null) {
            loadDir(catalog, projectRoot.resolve(".mewcode").resolve("agents"), "project");
        }

        // Layer 4: Plugin-level — reserved, always empty in this release

        return catalog;
    }

    // ── Built-in loading ──────────────────────────────────────────────────

    private static void loadBuiltins(AgentCatalog catalog) {
        try {
            Enumeration<URL> resources = AgentLoader.class.getClassLoader()
                    .getResources("subagent/builtin");
            while (resources.hasMoreElements()) {
                URL dirUrl = resources.nextElement();
                if ("file".equals(dirUrl.getProtocol())) {
                    Path dir = Path.of(dirUrl.toURI());
                    loadDir(catalog, dir, "builtin");
                } else {
                    // Inside JAR: scan via classpath listing
                    loadBuiltinsFromClasspath(catalog);
                }
            }
        } catch (Exception e) {
            // Try fallback: load builtins from classpath by name
            loadBuiltinsFromClasspath(catalog);
        }
    }

    private static void loadBuiltinsFromClasspath(AgentCatalog catalog) {
        // Load the three known built-in agents by name
        for (String name : List.of("general-purpose", "explore", "plan")) {
            String resourcePath = "/subagent/builtin/" + name + ".md";
            try (InputStream in = AgentLoader.class.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    SubAgentSpec spec = parseContent(content, resourcePath);
                    catalog.register(spec);
                }
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to load built-in agent definition: " + resourcePath, e);
            }
        }
    }

    // ── Directory loading ─────────────────────────────────────────────────

    private static void loadDir(AgentCatalog catalog, Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) continue;
                try {
                    String content = Files.readString(path);
                    SubAgentSpec spec = parseContent(content, path.toString());
                    catalog.register(spec);
                } catch (IllegalArgumentException e) {
                    System.err.println("[AgentLoader] 警告: " + e.getMessage() + " — 跳过");
                } catch (Exception e) {
                    System.err.println("[AgentLoader] 警告: 无法解析 " + path + ": "
                            + e.getMessage() + " — 跳过");
                }
            }
        } catch (IOException e) {
            // Directory unreadable — skip silently
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    /**
     * Parse a single agent definition file content.
     */
    static SubAgentSpec parseContent(String content, String source) {
        String trimmed = content.strip();

        String yamlBlock = null;
        String body = trimmed;

        if (trimmed.startsWith("---")) {
            int firstEnd = trimmed.indexOf("---", 3);
            if (firstEnd >= 0) {
                yamlBlock = trimmed.substring(3, firstEnd).strip();
                body = trimmed.substring(firstEnd + 3).strip();
            }
        }

        if (yamlBlock == null || yamlBlock.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition " + source + ": missing YAML frontmatter (---)");
        }

        Map<String, Object> frontmatter;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = YAML.readValue(yamlBlock, Map.class);
            frontmatter = raw != null ? raw : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Agent definition " + source + ": invalid YAML frontmatter: " + e.getMessage());
        }

        // Required fields
        String name = getString(frontmatter, "name");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition " + source + ": missing required field 'name'");
        }
        if (!name.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException(
                    "Agent definition " + source + ": invalid name '" + name
                            + "' (must be [a-z0-9-]{1,32})");
        }

        String description = getString(frontmatter, "description");
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition " + source + ": missing required field 'description'");
        }

        // Optional fields
        List<String> tools = getStringList(frontmatter, "tools");
        List<String> disallowedTools = getStringList(frontmatter, "disallowedTools");
        String model = getString(frontmatter, "model");
        String permissionMode = getString(frontmatter, "permissionMode");
        int maxTurns = getInt(frontmatter, "maxTurns", 0);
        boolean background = getBoolean(frontmatter, "background", false);

        // Validate enums
        if (model != null && !model.isEmpty() && !"inherit".equals(model)
                && !SubAgentSpec.VALID_MODELS.contains(model)) {
            System.err.println("[AgentLoader] 警告: " + source + ": unknown model '"
                    + model + "', fallback to 'inherit'");
            model = null;
        }

        if (permissionMode != null && !permissionMode.isEmpty()
                && !SubAgentSpec.VALID_PERMISSION_MODES.contains(permissionMode)) {
            System.err.println("[AgentLoader] 警告: " + source + ": unknown permissionMode '"
                    + permissionMode + "', fallback to 'default'");
            permissionMode = null;
        }

        String systemPrompt = body.isEmpty() ? null : body;

        return SubAgentSpec.of(name, description, tools, disallowedTools,
                systemPrompt, maxTurns, model, permissionMode, background);
    }

    // ── YAML helpers ──────────────────────────────────────────────────────

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        return defaultValue;
    }
}

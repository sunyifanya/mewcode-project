package com.mewcode.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads hook YAML files from {@code .mewcode/hooks/} with centralised validation.
 *
 * <p>One file per hook. Malformed files are skipped with warnings.
 */
public final class HookLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private HookLoader() {}

    /**
     * Result of loading hooks from disk.
     *
     * @param valid    the successfully parsed and validated hook configs
     * @param warnings non-fatal problems encountered during loading
     */
    public record LoadedHooks(List<HookConfig> valid, List<String> warnings) {}

    /**
     * Scan {@code hooksDir} for {@code .yaml} / {@code .yml} files, parse each,
     * and return the valid set plus any warnings.
     *
     * @param hooksDir path to {@code .mewcode/hooks/}
     * @return loaded hooks and warnings
     */
    public static LoadedHooks load(Path hooksDir) {
        List<HookConfig> valid = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (hooksDir == null || !Files.isDirectory(hooksDir)) {
            return new LoadedHooks(valid, warnings);
        }

        List<Path> yamlFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(hooksDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted().forEach(yamlFiles::add);
        } catch (IOException e) {
            warnings.add("无法读取 hooks 目录: " + e.getMessage());
            return new LoadedHooks(valid, warnings);
        }

        for (Path file : yamlFiles) {
            try {
                HookConfig config = YAML_MAPPER.readValue(file.toFile(), HookConfig.class);
                List<String> issues = validate(config, file);
                if (issues.isEmpty()) {
                    valid.add(config);
                } else {
                    warnings.addAll(issues);
                }
            } catch (IOException e) {
                warnings.add("Hook YAML 解析失败 (" + file.getFileName() + "): " + e.getMessage());
            }
        }

        return new LoadedHooks(valid, warnings);
    }

    // ---- Validation ----

    /**
     * Validate a single hook config. Returns list of issues; empty = valid.
     */
    static List<String> validate(HookConfig config, Path file) {
        List<String> issues = new ArrayList<>();
        String loc = file.getFileName().toString();

        // id
        if (config.id() == null || config.id().isBlank()) {
            issues.add(loc + ": id is required");
        }

        // event
        if (config.event() == null) {
            issues.add(loc + ": event is required and must be one of the 9 valid event names");
        }

        // action
        HookAction action = config.action();
        if (action == null || action.type() == null) {
            issues.add(loc + ": action.type is required");
            return issues;
        }

        switch (action.type()) {
            case COMMAND -> {
                if (action.command() == null || action.command().isBlank()) {
                    issues.add(loc + ": command action requires 'command' field");
                }
                // reject + background is a conflict
                if (config.reject() && action.background()) {
                    issues.add(loc + ": reject and background are mutually exclusive");
                }
            }
            case PROMPT -> {
                if (action.message() == null || action.message().isBlank()) {
                    issues.add(loc + ": prompt action requires 'message' field");
                }
            }
            case HTTP -> {
                if (action.url() == null || action.url().isBlank()) {
                    issues.add(loc + ": http action requires 'url' field");
                }
            }
            case SUB_AGENT -> {
                // Placeholder — no validation needed yet
            }
        }

        // condition mode
        if (config.conditionGroup() != null && config.conditionGroup().mode() != null) {
            String mode = config.conditionGroup().mode().strip().toLowerCase();
            if (!mode.equals("all") && !mode.equals("any")) {
                issues.add(loc + ": if.mode must be 'all' or 'any', got '" + mode + "' — defaulting to 'all'");
            }
        }

        return issues;
    }
}

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Finds files matching a glob pattern under a directory.
 * Results are sorted by last-modified time descending.
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 500;

    private final Path workingDir;

    public GlobTool(String workingDirectory) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "按 glob 模式递归匹配文件，返回命中文件路径列表，按修改时间降序排列。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.<String, Object>of(
                "type", "object",
                "properties", Map.<String, Object>of(
                        "pattern", Map.<String, Object>of("type", "string", "description", "Glob 模式，如 **/*.java、src/**/*.ts、*.md"),
                        "path", Map.<String, Object>of("type", "string", "description", "搜索起始目录，默认为项目根目录")
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean shouldDefer() {
        return false; // built-in tool, always visible
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pattern = (String) params.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return new ToolResult(false, "缺少必填参数 pattern", "INVALID_PARAMS");
        }

        String pathStr = (String) params.get("path");
        Path searchDir = workingDir;
        if (pathStr != null && !pathStr.isBlank()) {
            searchDir = workingDir.resolve(pathStr).normalize();
            if (!searchDir.startsWith(workingDir)) {
                return new ToolResult(false, "路径穿越检测: " + pathStr, "PATH_TRAVERSAL");
            }
        }

        if (!Files.exists(searchDir) || !Files.isDirectory(searchDir)) {
            return new ToolResult(false, "目录不存在或不是目录: " + searchDir, "IO_ERROR");
        }

        try {
            // Build a PathMatcher for the glob
            java.nio.file.FileSystem fs = searchDir.getFileSystem();
            // Build full pattern: "glob:" + relativePattern resolved to absolute
            // We match against the filename, not the full path, for simplicity.
            // For ** patterns, we need to match against the relative path.
            Path searchRoot = searchDir;
            String globPattern = "glob:" + pattern;
            final java.nio.file.PathMatcher matcher = fs.getPathMatcher(globPattern);

            // Java's PathMatcher doesn't match root-level files when pattern starts with **/
            // (because the / in **/ requires a directory separator that root files don't have).
            // Create a fallback matcher that strips the **/ prefix for root-level matching.
            java.nio.file.PathMatcher altMatcher = pattern.startsWith("**/")
                    ? fs.getPathMatcher("glob:" + pattern.substring(3))
                    : null;

            // Collect matching files with their last-modified time
            List<Map.Entry<Path, Long>> results = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(searchDir)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    // Match the relative path from searchDir for ** patterns to work
                    Path relative = searchRoot.relativize(file);
                    if (matcher.matches(relative) || (altMatcher != null && altMatcher.matches(relative))) {
                        try {
                            long mtime = Files.getLastModifiedTime(file).toMillis();
                            results.add(new AbstractMap.SimpleEntry<>(file, mtime));
                        } catch (IOException ignored) {
                            results.add(new AbstractMap.SimpleEntry<>(file, 0L));
                        }
                    }
                });
            }

            // Sort by mtime descending
            results.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            boolean truncated = results.size() > MAX_RESULTS;
            int limit = Math.min(results.size(), MAX_RESULTS);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                Path absPath = results.get(i).getKey();
                String displayPath;
                try {
                    displayPath = workingDir.relativize(absPath).toString();
                } catch (IllegalArgumentException e) {
                    displayPath = absPath.toString();
                }
                sb.append(displayPath).append("\n");
            }

            if (results.isEmpty()) {
                sb.append("未找到匹配文件");
            }
            if (truncated) {
                sb.append(String.format("... (截断，共 %d 个匹配文件)%n", results.size()));
            }

            return new ToolResult(true, sb.toString().trim());
        } catch (IOException e) {
            return new ToolResult(false, "搜索文件失败: " + e.getMessage(), "IO_ERROR");
        }
    }
}

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Searches file contents with a regular expression.
 * Returns file path, line number, and matching line content.
 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;

    private final Path workingDir;

    public GrepTool(String workingDirectory) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public boolean shouldDefer() {
        return false; // built-in tool, always visible
    }

    @Override
    public String getDescription() {
        return "用正则表达式搜索文件内容，返回匹配文件路径、行号和匹配行内容。" +
               "支持按 glob 模式过滤文件类型。结果上限 " + MAX_RESULTS + " 条。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.<String, Object>of(
                "type", "object",
                "properties", Map.<String, Object>of(
                        "pattern", Map.<String, Object>of("type", "string", "description", "正则表达式，用于匹配文件内容"),
                        "path", Map.<String, Object>of("type", "string", "description", "搜索起始目录，默认为项目根目录"),
                        "glob", Map.<String, Object>of("type", "string", "description", "文件过滤 glob 模式，如 *.java、*.{ts,tsx}。只搜索匹配此模式的文件")
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
    public ToolResult execute(Map<String, Object> params) {
        String patternStr = (String) params.get("pattern");
        if (patternStr == null || patternStr.isBlank()) {
            return new ToolResult(false, "缺少必填参数 pattern", "INVALID_PARAMS");
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return new ToolResult(false, "正则表达式无效: " + e.getMessage(), "INVALID_PARAMS");
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

        String globFilter = (String) params.get("glob");
        java.nio.file.PathMatcher globMatcher = null;
        java.nio.file.PathMatcher altGlobMatcher = null;
        if (globFilter != null && !globFilter.isBlank()) {
            try {
                globMatcher = searchDir.getFileSystem().getPathMatcher("glob:" + globFilter);
                // Java's PathMatcher doesn't match root-level files when pattern starts with **/
                if (globFilter.startsWith("**/")) {
                    altGlobMatcher = searchDir.getFileSystem().getPathMatcher("glob:" + globFilter.substring(3));
                }
            } catch (Exception e) {
                return new ToolResult(false, "文件过滤 glob 模式无效: " + e.getMessage(), "INVALID_PARAMS");
            }
        }

        List<String> results = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> fileStream = Files.walk(searchDir)) {
            List<Path> files = fileStream.filter(Files::isRegularFile).toList();

            for (Path file : files) {
                if (truncated) break;

                // Apply glob filter if present
                if (globMatcher != null) {
                    Path relative = searchDir.relativize(file);
                    if (!globMatcher.matches(relative) && (altGlobMatcher == null || !altGlobMatcher.matches(relative))) continue;
                }

                // Skip binary / very large files (heuristic: > 1 MB)
                try {
                    if (Files.size(file) > 1_000_000) continue;
                } catch (IOException e) {
                    continue;
                }

                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (results.size() >= MAX_RESULTS) {
                            truncated = true;
                            break;
                        }
                        String line = lines.get(i);
                        if (regex.matcher(line).find()) {
                            String displayPath;
                            try {
                                displayPath = workingDir.relativize(file).toString();
                            } catch (IllegalArgumentException e) {
                                displayPath = file.toString();
                            }
                            results.add(displayPath + ":" + (i + 1) + ": " + line);
                        }
                    }
                } catch (IOException e) {
                    // Skip files we can't read
                }
            }
        } catch (IOException e) {
            return new ToolResult(false, "搜索文件失败: " + e.getMessage(), "IO_ERROR");
        }

        StringBuilder sb = new StringBuilder();
        for (String r : results) {
            sb.append(r).append("\n");
        }
        if (results.isEmpty()) {
            sb.append("无匹配");
        }
        if (truncated) {
            sb.append(String.format("... (截断，共找到超过 %d 条匹配)%n", MAX_RESULTS));
        }

        return new ToolResult(true, sb.toString().trim());
    }
}

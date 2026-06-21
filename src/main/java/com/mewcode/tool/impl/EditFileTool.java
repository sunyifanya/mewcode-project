package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performs exact string replacement in a file.
 * old_string must match exactly once (including all whitespace / indentation).
 * - Zero matches → error with a file snippet so the model can adjust.
 * - Multiple matches → error listing every match location so the model can add context.
 * - Single match → replace and write back.
 */
public class EditFileTool implements Tool {

    private final Path workingDir;

    public EditFileTool(String workingDirectory) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public String getDescription() {
        return "在文件中做精确字符串替换。old_string在文件内容中完全匹配（含空白和缩进）。" +
               "匹配唯一时替换并返回成功；匹配零次返回错误及文件片段；匹配多次返回各位置供模型追加更多上下文。";
    }

    @Override
    public boolean shouldDefer() {
        return false; // built-in tool, always visible
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.<String, Object>of(
                        "file_path", Map.<String, Object>of("type", "string", "description", "要修改的文件路径，相对于项目根目录"),
                        "old_string", Map.<String, Object>of("type", "string", "description", "要替换的原文，必须与文件中的文本完全一致（含缩进和空白）"),
                        "new_string", Map.<String, Object>of("type", "string", "description", "替换后的新文本")
                ),
                "required", List.of("file_path", "old_string", "new_string")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePathStr = (String) params.get("file_path");
        String oldString = (String) params.get("old_string");
        String newString = (String) params.get("new_string");

        if (filePathStr == null || filePathStr.isBlank()) {
            return new ToolResult(false, "缺少必填参数 file_path", "INVALID_PARAMS");
        }
        if (oldString == null) {
            return new ToolResult(false, "缺少必填参数 old_string", "INVALID_PARAMS");
        }
        if (newString == null) {
            return new ToolResult(false, "缺少必填参数 new_string", "INVALID_PARAMS");
        }

        Path filePath = workingDir.resolve(filePathStr).normalize();
        if (!filePath.startsWith(workingDir)) {
            return new ToolResult(false, "路径穿越检测: " + filePathStr, "PATH_TRAVERSAL");
        }

        if (!Files.exists(filePath)) {
            return new ToolResult(false, "文件不存在: " + filePathStr, "FILE_NOT_FOUND");
        }

        String content;
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath);
            content = String.join("\n", lines);
        } catch (IOException e) {
            return new ToolResult(false, "读取文件失败: " + e.getMessage(), "IO_ERROR");
        }

        // Find all match positions
        List<Integer> matchPositions = new ArrayList<>();
        int fromIndex = 0;
        while (true) {
            int pos = content.indexOf(oldString, fromIndex);
            if (pos == -1) break;
            matchPositions.add(pos);
            fromIndex = pos + 1; // move forward by 1 to allow overlapping matches to be counted
        }

        if (matchPositions.isEmpty()) {
            // Zero matches — show a file snippet
            String snippet = buildSnippet(lines, Math.min(30, lines.size()));
            return new ToolResult(false,
                    "未找到匹配文本。请检查 old_string 是否与文件内容完全一致（含缩进和空白）。" +
                    "以下是文件开头的内容片段供对照：\n\n" + snippet,
                    "MATCH_NOT_FOUND");
        }

        if (matchPositions.size() > 1) {
            // Multiple matches — show each location
            StringBuilder sb = new StringBuilder();
            sb.append("匹配到 ").append(matchPositions.size()).append(" 处，请追加更多上下文使匹配唯一：\n");
            for (int pos : matchPositions) {
                int lineNum = positionToLine(content, pos);
                String context = extractContext(lines, lineNum - 1); // 0-indexed
                sb.append(String.format("- 第 %d 行: %s%n", lineNum, context));
            }
            return new ToolResult(false, sb.toString(), "MULTIPLE_MATCHES");
        }

        // Single match — replace and write
        int pos = matchPositions.getFirst();
        int lineNum = positionToLine(content, pos);
        String newContent = content.substring(0, pos) + newString + content.substring(pos + oldString.length());

        try {
            Files.writeString(filePath, newContent);
            return new ToolResult(true, "已在 " + filePathStr + " 第 " + lineNum + " 行完成替换");
        } catch (IOException e) {
            return new ToolResult(false, "写入文件失败: " + e.getMessage(), "IO_ERROR");
        }
    }

    /** Count newlines before the given position to determine the 1-based line number. */
    private int positionToLine(String content, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Extract a short snippet from a line (trimmed, max 80 chars). */
    private String extractContext(List<String> lines, int lineIdx) {
        if (lineIdx < 0 || lineIdx >= lines.size()) return "";
        String line = lines.get(lineIdx).trim();
        if (line.length() > 80) line = line.substring(0, 80) + "...";
        return line;
    }

    /** Build a line-numbered snippet from a range of lines. */
    private String buildSnippet(List<String> lines, int endLineIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < endLineIdx && i < lines.size(); i++) {
            sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
        }
        if (endLineIdx < lines.size()) {
            sb.append(String.format("... (文件共 %d 行)%n", lines.size()));
        }
        return sb.toString();
    }
}

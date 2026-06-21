package com.mewcode.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves {@code @include} directives within instruction files.
 *
 * <p>Rules:
 * <ul>
 *   <li>Recognizes {@code @include path/to/file.md} lines (line must start with {@code @include }).</li>
 *   <li>Paths are relative to the base directory of the file containing the directive.</li>
 *   <li>Resolved absolute path must stay within the project root.</li>
 *   <li>Max depth 5; cycles detected via visited set.</li>
 * </ul>
 */
public class IncludeResolver {

    static final int MAX_DEPTH = 5;

    /**
     * Resolve all {@code @include} directives in the given content.
     *
     * @param baseDir the directory against which relative include paths are resolved
     * @param content the text content to scan for @include lines
     * @param projectRoot the project root; resolved absolute paths must start with this
     * @return the content with all @include directives replaced by included file contents
     */
    public static String resolve(Path baseDir, String content, Path projectRoot) {
        return resolveInternal(baseDir, content, projectRoot, 0, new HashSet<>());
    }

    private static String resolveInternal(Path baseDir, String content, Path projectRoot,
                                          int depth, Set<Path> visited) {
        if (depth > MAX_DEPTH) {
            System.err.println("警告: @include 嵌套深度超过上限 " + MAX_DEPTH + "，跳过后续 include");
            return content;
        }

        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n", -1);

        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("@include ")) {
                String includePath = trimmed.substring("@include ".length()).trim();
                if (includePath.isEmpty()) {
                    result.append(line).append('\n');
                    continue;
                }

                // Resolve the include path
                Path resolved = baseDir.resolve(includePath).normalize();
                Path absolute;
                try {
                    absolute = resolved.toRealPath();
                } catch (IOException e) {
                    System.err.println("警告: @include 文件不存在: " + includePath + " (解析为 " + resolved + ")");
                    result.append("[未找到: ").append(includePath).append("]\n");
                    continue;
                }

                // Security: path must be within project root
                if (!absolute.startsWith(projectRoot)) {
                    System.err.println("警告: @include 路径跳出项目目录，已拒绝: " + includePath);
                    result.append("[拒绝: ").append(includePath).append("]\n");
                    continue;
                }

                // Cycle detection
                if (visited.contains(absolute)) {
                    System.err.println("警告: @include 检测到循环引用: " + includePath);
                    result.append("[循环引用: ").append(includePath).append("]\n");
                    continue;
                }

                // Read the included file
                String includedContent;
                try {
                    includedContent = Files.readString(absolute);
                } catch (IOException e) {
                    System.err.println("警告: @include 无法读取文件: " + absolute);
                    result.append("[无法读取: ").append(includePath).append("]\n");
                    continue;
                }

                // Mark visited and recurse
                Set<Path> newVisited = new HashSet<>(visited);
                newVisited.add(absolute);
                String expanded = resolveInternal(absolute.getParent(), includedContent, projectRoot,
                        depth + 1, newVisited);
                result.append(expanded).append('\n');
            } else {
                result.append(line).append('\n');
            }
        }

        return result.toString();
    }
}

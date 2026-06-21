package com.mewcode.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads project/user instruction files with priority-based concatenation.
 *
 * <p>Three layers, loaded in priority order (highest first):
 * <ol>
 *   <li>{@code {projectRoot}/MEWCODE.md}</li>
 *   <li>{@code {projectRoot}/.mewcode/MEWCODE.md}</li>
 *   <li>{@code ~/.mewcode/MEWCODE.md}</li>
 * </ol>
 *
 * <p>Each file is scanned for {@code @include} directives (resolved by
 * {@link IncludeResolver}) before being concatenated.
 * Missing files are silently skipped. Include errors are logged as
 * warnings and do not block startup.
 */
public class InstructionsLoader {

    private static final String FILE_NAME = "MEWCODE.md";

    /**
     * Load and concatenate instructions from all three layers.
     *
     * @param workDir the project working directory (used to resolve project root)
     * @return concatenated instruction text, or empty string if no files exist
     */
    public static String load(String workDir) {
        Path projectRoot = Path.of(workDir).toAbsolutePath().normalize();
        Path userHome = Path.of(System.getProperty("user.home", "."));

        String[] results = new String[3];

        // Layer 1: project root MEWCODE.md
        Path layer1 = projectRoot.resolve(FILE_NAME);
        results[0] = loadAndResolve(layer1, layer1.getParent(), projectRoot);

        // Layer 2: .mewcode/MEWCODE.md
        Path layer2 = projectRoot.resolve(".mewcode").resolve(FILE_NAME);
        results[1] = loadAndResolve(layer2, layer2.getParent(), projectRoot);

        // Layer 3: ~/.mewcode/MEWCODE.md
        Path layer3 = userHome.resolve(".mewcode").resolve(FILE_NAME);
        results[2] = loadAndResolve(layer3, layer3.getParent(), projectRoot);

        StringBuilder combined = new StringBuilder();
        for (String r : results) {
            if (r != null && !r.isEmpty()) {
                if (!combined.isEmpty()) {
                    combined.append('\n');
                }
                combined.append(r);
            }
        }
        return combined.toString();
    }

    /**
     * Read a file and resolve its @include directives.
     *
     * @param filePath    path to the instruction file
     * @param baseDir     directory for relative include resolution
     * @param projectRoot project root for security boundary checks
     * @return resolved content, or empty string if the file doesn't exist or is unreadable
     */
    private static String loadAndResolve(Path filePath, Path baseDir, Path projectRoot) {
        if (!Files.exists(filePath)) {
            return "";
        }
        try {
            String raw = Files.readString(filePath);
            return IncludeResolver.resolve(baseDir, raw, projectRoot);
        } catch (IOException e) {
            System.err.println("警告: 无法读取指令文件: " + filePath + " (" + e.getMessage() + ")");
            return "";
        }
    }
}

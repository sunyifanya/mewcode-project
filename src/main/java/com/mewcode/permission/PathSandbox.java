package com.mewcode.permission;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Restricts file writes to paths inside the project root directory or /tmp.
 *
 * <p>Resolves symbolic links with {@link Path#toRealPath()} before checking
 * the prefix, preventing symlink-based sandbox escapes.
 */
public class PathSandbox {

    private final Path projectRoot;

    /**
     * @param projectRoot absolute, normalised path to the project root
     */
    public PathSandbox(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Check whether {@code targetPath} resides inside the project root or /tmp.
     *
     * @param targetPath the (possibly relative, possibly symlinked) path to check
     * @return ALLOW if the resolved real path starts with {@code projectRoot} or /tmp,
     *         DENY otherwise
     */
    public PermissionResult check(Path targetPath) {
        // Resolve against project root if relative
        Path absolute = projectRoot.resolve(targetPath).normalize();

        Path realPath;
        try {
            realPath = absolute.toRealPath();
        } catch (IOException e) {
            // File doesn't exist yet (e.g. write_file creating a new file).
            // Check the parent directory instead.
            Path parent = absolute.getParent();
            if (parent == null) {
                return PermissionResult.deny(
                        "无法解析路径: " + targetPath,
                        "请使用项目目录内的路径");
            }
            try {
                Path realParent = parent.toRealPath();
                realPath = realParent.resolve(absolute.getFileName());
            } catch (IOException e2) {
                return PermissionResult.deny(
                        "路径不可访问: " + targetPath + " (" + e2.getMessage() + ")",
                        "请使用项目目录内的路径");
            }
        }

        // Allow project root
        if (realPath.startsWith(projectRoot)) {
            return PermissionResult.allow("路径在项目目录内");
        }

        // Allow /tmp (and /tmp-equivalent on Windows)
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (realPath.startsWith(tmpDir)) {
            return PermissionResult.allow("路径在临时目录内");
        }

        return PermissionResult.deny(
                "路径在项目目录外: " + targetPath,
                "请将文件写到项目目录或临时目录内");
    }
}

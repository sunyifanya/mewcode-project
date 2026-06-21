package com.mewcode.command;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runtime context passed to command handlers, providing access to the current
 * application state (permission mode, token counts, model, etc.).
 *
 * <p>All dynamic values use functional interfaces so command handlers
 * don't bind to any specific UI framework or component.
 */
public record CommandContext(
        String args,
        String workDir,
        String model,
        Supplier<String> permissionMode,
        BooleanSupplier planMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo
) {}
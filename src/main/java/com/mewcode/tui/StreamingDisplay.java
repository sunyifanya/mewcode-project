package com.mewcode.tui;

import com.mewcode.provider.ChunkType;

import java.io.PrintWriter;

/**
 * Renders streaming LLM output to the terminal with ANSI color coding.
 *
 * THINKING chunks are displayed dimmed with a [思考] prefix.
 * TEXT chunks are printed in default terminal color.
 * ERROR chunks are printed in red.
 */
public class StreamingDisplay {

    // ANSI escape codes
    private static final String ANSI_GRAY = "\033[90m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_RESET = "\033[0m";

    private final PrintWriter writer;
    private boolean thinkingPrefixPrinted = false;

    public StreamingDisplay(PrintWriter writer) {
        this.writer = writer;
    }

    public void onChunk(String text, ChunkType type) {
        if (text == null || text.isEmpty()) return;

        switch (type) {
            case THINKING -> {
                if (!thinkingPrefixPrinted) {
                    writer.print(ANSI_GRAY + "[思考] ");
                    thinkingPrefixPrinted = true;
                }
                writer.print(ANSI_GRAY + text + ANSI_RESET);
            }
            case TEXT -> {
                if (thinkingPrefixPrinted) {
                    writer.print(ANSI_RESET + "\n");
                    thinkingPrefixPrinted = false;
                }
                writer.print(text);
            }
            case ERROR -> {
                if (thinkingPrefixPrinted) {
                    writer.print(ANSI_RESET + "\n");
                    thinkingPrefixPrinted = false;
                }
                writer.print(ANSI_RED + text + ANSI_RESET);
            }
        }
        writer.flush();
    }

    public void onComplete() {
        if (thinkingPrefixPrinted) {
            writer.print(ANSI_RESET);
            thinkingPrefixPrinted = false;
        }
        writer.println();
        writer.flush();
    }

    public void onError(Throwable t) {
        if (thinkingPrefixPrinted) {
            writer.print(ANSI_RESET);
            thinkingPrefixPrinted = false;
        }
        writer.println(ANSI_RED + "\n[错误] " + t.getMessage() + ANSI_RESET);
        writer.flush();
    }
}
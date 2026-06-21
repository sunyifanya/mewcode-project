package com.mewcode.command;

/**
 * Dispatch style for a slash command.
 */
public enum CommandType {
    /** Synchronous handler that returns text output. */
    LOCAL,
    /** TUI action (clear screen, mode switch) -- no text output. */
    LOCAL_UI,
    /** Generates a prompt string sent to the LLM agent. */
    PROMPT
}

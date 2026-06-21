package com.mewcode.provider;

/**
 * Type of a streaming chunk delivered to the display layer.
 */
public enum ChunkType {
    /** Claude extended-thinking content — displayed dimmed. */
    THINKING,
    /** Normal assistant reply text. */
    TEXT,
    /** Error information to surface to the user. */
    ERROR,
    /** Complete tool-call JSON delivered when a tool_use content block finishes. */
    TOOL_CALL
}

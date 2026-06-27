package com.mewcode.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single condition within a hook's "if" block.
 *
 * @param variable the context variable to check (tool, event, file_path, message, args.&lt;key&gt;)
 * @param operator matching operator: == (equals), != (not-equals), =~ (regex), or glob
 * @param value    the value to match against
 */
public record HookCondition(
        @JsonProperty("variable") String variable,
        @JsonProperty("operator") String operator,
        @JsonProperty("value") String value
) {}

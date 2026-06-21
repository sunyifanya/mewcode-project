package com.mewcode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds tool-related configuration nested under "tool:" in mewcode.yaml.
 */
public class ToolConfig {

    @JsonProperty("timeout_seconds")
    private int timeoutSeconds = 30;

    @JsonProperty("working_directory")
    private String workingDirectory = ".";

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
}

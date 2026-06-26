package com.mewcode.conversation;

import java.util.List;

/**
 * Context values that the reminder composer needs at injection time.
 *
 * Provided by the caller (AgentLoop / ConversationManager) each time
 * {@code getMessages()} is invoked.
 */
public final class ReminderContext {

    private final int iteration;
    private final boolean planMode;
    private final String workingDirectory;
    private final String platform;
    private final String currentDate;
    private final List<String> deferredToolNames;
    private final String activeSkillsContent;

    public ReminderContext(int iteration, boolean planMode,
                           String workingDirectory, String platform, String currentDate,
                           List<String> deferredToolNames,
                           String activeSkillsContent) {
        this.iteration = iteration;
        this.planMode = planMode;
        this.workingDirectory = workingDirectory;
        this.platform = platform;
        this.currentDate = currentDate;
        this.deferredToolNames = deferredToolNames != null ? List.copyOf(deferredToolNames) : List.of();
        this.activeSkillsContent = activeSkillsContent != null ? activeSkillsContent : "";
    }

    /** Backward-compatible constructor without activeSkillsContent. */
    public ReminderContext(int iteration, boolean planMode,
                           String workingDirectory, String platform, String currentDate,
                           List<String> deferredToolNames) {
        this(iteration, planMode, workingDirectory, platform, currentDate, deferredToolNames, "");
    }

    /** 0-based iteration index (0 = first round). */
    public int getIteration() { return iteration; }
    public boolean isPlanMode() { return planMode; }
    public String getWorkingDirectory() { return workingDirectory; }
    public String getPlatform() { return platform; }
    public String getCurrentDate() { return currentDate; }

    /** Names of deferred tools that have not yet been discovered, or empty list. */
    public List<String> getDeferredToolNames() { return deferredToolNames; }

    /** Active skills SOP content for the "已激活Skill" module, or empty string. */
    public String getActiveSkillsContent() { return activeSkillsContent; }
}

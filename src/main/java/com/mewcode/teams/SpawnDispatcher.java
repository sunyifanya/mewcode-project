package com.mewcode.teams;

import com.mewcode.agent.AgentLoop;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.provider.LLMProvider;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Path;

/**
 * Unified dispatcher for teammate spawning across all backends.
 */
public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LLMProvider provider,
            ToolRegistry registry,
            String protocol,
            String workdir,
            int maxTurns,
            int streamTimeoutSeconds,
            com.mewcode.permission.PermissionChecker permissionChecker
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            String paneId
    ) {}

    private SpawnDispatcher() {}

    /**
     * Spawns a teammate using the appropriate backend for the team's mode.
     */
    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        switch (mode) {
            case IN_PROCESS -> {
                // Create a conversation manager for this teammate
                ConversationManager conv = new ConversationManager();

                // Create a minimal placeholder AgentLoop (TeammateRunner creates fresh ones per turn)
                var eventQueue = new java.util.concurrent.LinkedBlockingQueue<com.mewcode.agent.AgentEvent>(256);
                AgentLoop agent = new AgentLoop(
                        config.provider(),
                        config.registry(),
                        conv,
                        eventQueue,
                        config.maxTurns(),
                        config.streamTimeoutSeconds(),
                        config.permissionChecker()
                );
                agent.setWorkingDirectory(config.workdir() != null ? config.workdir()
                        : System.getProperty("user.dir"));
                agent.configureAsSubAgent(config.memberName(), null, config.maxTurns(), "dontAsk");

                var member = team.addMember(config.memberName(), agent, conv);
                // Store creation parameters so TeammateRunner can create fresh AgentLoops per turn
                member.provider = config.provider();
                member.toolRegistry = config.registry();
                member.protocol = config.protocol();
                member.maxTurns = config.maxTurns();
                member.streamTimeoutSeconds = config.streamTimeoutSeconds();
                member.permissionChecker = config.permissionChecker();
                member.workDir = config.workdir();
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            case TMUX -> {
                // Write task to mailbox so first poll picks it up
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String cliCommand = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.getName(), config.memberName(), cliCommand);
                recordExternalMember(team, config.memberName(), paneId);
                return new SpawnResult(mode, paneId);
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }

    /**
     * Builds the shell command for a worker process.
     * Format: cd '<workdir>' && '<mewcode>' --teammate --team-name <t> --agent-name <n>
     */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir != null ? workdir : System.getProperty("user.dir");
        // Find mewcode executable (assume it's the current JAR or on PATH)
        String mewcode = ProcessHandle.current().info().command().orElse("mewcode");
        return "cd %s && %s --teammate --team-name %s --agent-name %s".formatted(
                shellQuote(wd), shellQuote(mewcode), shellQuote(teamName), shellQuote(memberName));
    }

    static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void recordExternalMember(TeamManager.Team team, String name, String paneId) {
        // For external backends, create a placeholder member
        var member = new TeamManager.Member(name, null, null);
        member.active = true;
        synchronized (team) {
            team.members.put(name, member);
        }
    }
}

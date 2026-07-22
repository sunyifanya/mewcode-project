package com.mewcode.teams;

import com.mewcode.agent.AgentLoop;
import com.mewcode.agent.AgentEvent;
import com.mewcode.conversation.ConversationManager;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main loop for in-process teammates.
 * After completing the initial task, the teammate polls its mailbox
 * and re-enters the agent loop when new messages arrive.
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**
     * Runs a teammate agent loop in the current thread. Blocks until shutdown
     * or context cancellation (thread interrupt).
     *
     * @param team           the team this member belongs to
     * @param member         the team member (with pre-configured AgentLoop and ConversationManager)
     * @param initialPrompt  the first task prompt
     * @param addendum       optional system reminder text (e.g., team membership info)
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // Inject any pending mailbox messages
        injectPendingMessages(team, member.getName(), member.conv);

        // First turn: use initial prompt
        member.conv.addUserMessage(initialPrompt);
        runAgentTurn(member);

        // Send idle notification
        team.sendMessage(member.getName(), LEAD_NAME,
                createIdleNotification(member.getName(), "completed initial task"));

        // Subsequent turns: wait for mailbox messages
        runIdleLoop(team, member);
    }

    /**
     * Drains lead's mailbox across all teams, returning formatted notification strings.
     * Called by the Lead's notificationFn each iteration.
     */
    public static List<String> drainLeadMailbox(TeamManager teamManager) {
        if (teamManager == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamManager.listTeams()) {
            var team = teamManager.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            var stringBuilder = new StringBuilder();
            stringBuilder.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var message : messages) {
                stringBuilder.append("from=").append(message.from()).append(": ").append(message.text()).append("\n");
            }
            stringBuilder.append("</team-notification>");
            result.add(stringBuilder.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**
     * Builds the system reminder addendum for a teammate.
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("You are a member of team \"").append(teamName).append("\". ");
        stringBuilder.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            stringBuilder.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        stringBuilder.append("You can communicate with teammates using the SendMessage tool.\n");
        stringBuilder.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        stringBuilder.append("When you finish your current task, simply stop calling tools — ");
        stringBuilder.append("an idle notification will be sent to the lead automatically.");
        return stringBuilder.toString();
    }

    /**
     * Injects unread mailbox messages as a system reminder into the conversation.
     */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s: %s (at %s)".formatted(memberName, reason,
                java.time.Instant.now().toString());
    }

    /**
     * Runs purely the idle polling loop (after first turn was done synchronously).
     * Waits for mailbox messages and runs follow-up agent turns.
     */
    public static void runIdleLoop(TeamManager.Team team, TeamManager.Member member) {
        while (!Thread.currentThread().isInterrupted()) {
            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown || result.prompt == null) break;

            member.conv.addUserMessage(result.prompt);
            runAgentTurn(member);

            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "completed follow-up"));
        }
        member.active = false;
    }

    private record WaitResult(String prompt, boolean shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, true);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (isShutdownRequest(msg.text())) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, true);
                }
            }

            // Format as prompt
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), false);
        }
        return new WaitResult(null, true);
    }

    /**
     * Runs one turn of the member's agent loop, creating a fresh AgentLoop instance.
     * The ConversationManager persists across turns, carrying the full history.
     */
    private static void runAgentTurn(TeamManager.Member member) {
        try {
            var eventQueue = new LinkedBlockingQueue<com.mewcode.agent.AgentEvent>(256);
            AgentLoop turnAgent = new AgentLoop(
                    member.provider,
                    member.toolRegistry,
                    member.conv,
                    eventQueue,
                    member.maxTurns,
                    member.streamTimeoutSeconds,
                    member.permissionChecker
            );
            turnAgent.setWorkingDirectory(member.workDir != null ? member.workDir
                    : System.getProperty("user.dir"));
            turnAgent.configureAsSubAgent(member.name, null, member.maxTurns, "dontAsk");

            turnAgent.runToCompletion();
        } catch (Exception e) {
            // Agent error — conversation is at whatever state it reached
        }
    }
}

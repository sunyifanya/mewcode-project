package com.mewcode.teams;

import com.mewcode.agent.AgentLoop;
import com.mewcode.conversation.ConversationManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages multi-agent teams with mailbox-based communication.
 */
public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX }

    private final Map<String, Team> teams = new LinkedHashMap<>();

    public synchronized Team createTeam(String name, TeamMode mode) {
        Team team = new Team(name, mode);
        teams.put(name, team);
        return team;
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            team.stopAll();
        }
    }

    public synchronized List<String> listTeams() {
        return new ArrayList<>(teams.keySet());
    }

    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    public static TeamMode detectBackend() {
        if (System.getenv("TMUX") != null && !System.getenv("TMUX").isEmpty()) {
            return TeamMode.TMUX;
        }
        try {
            Process p = new ProcessBuilder("which", "tmux").start();
            if (p.waitFor() == 0) return TeamMode.TMUX;
        } catch (Exception ignored) {}
        return TeamMode.IN_PROCESS;
    }

    // ── Static helpers ──────────────────────────────────────────────────

    private static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.dir"), ".mewcode", "teams");
    }

    // ── Inner classes ──────────────────────────────────────────────────

    public static class Team {
        final String name;
        final TeamMode mode;
        final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;

        public Team(String name, TeamMode mode) {
            this.name = name;
            this.mode = mode;
            this.mailBox = new FileMailBox(teamsBaseDir().resolve(name).resolve("inboxes"));
        }

        public String getName() { return name; }
        public TeamMode getMode() { return mode; }

        public FileMailBox getMailBox() { return mailBox; }

        public synchronized Member addMember(String name, AgentLoop agent, ConversationManager conv) {
            Member member = new Member(name, agent, conv);
            members.put(name, member);
            return member;
        }

        public synchronized void stopMember(String name) {
            Member member = members.get(name);
            if (member != null) {
                member.active = false;
                if (member.thread != null) {
                    member.thread.interrupt();
                }
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) m.thread.interrupt();
            }
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return new ArrayList<>(members.keySet());
        }

        public void sendMessage(String from, String to, String content) {
            mailBox.send(to, new FileMailBox.MailMessage(from, content));
        }
    }

    public static class Member {
        public final String name;
        public volatile AgentLoop agent;
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
        // Stored for creating new AgentLoop instances each turn
        public com.mewcode.provider.LLMProvider provider;
        public com.mewcode.tool.ToolRegistry toolRegistry;
        public String protocol;
        public int maxTurns;
        public int streamTimeoutSeconds;
        public com.mewcode.permission.PermissionChecker permissionChecker;
        public String workDir;

        public Member(String name, AgentLoop agent, ConversationManager conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
        }

        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

}

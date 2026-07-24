package com.mewcode.teams;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team coordination tools: SendMessage, TeamCreate, TeamDelete, TeamStopMember, TeamMerge.
 */
public final class TeamTools {

    private TeamTools() {}

    // ── SendMessage ────────────────────────────────────────────────────

    public static class SendMessageTool implements Tool {
        private final TeamManager teamManager;
        private final String senderName;

        public SendMessageTool(TeamManager teamMgr, String senderName) {
            this.teamManager = teamMgr;
            this.senderName = senderName;
        }

        @Override public String getName() { return "SendMessage"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String getDescription() {
            return "向团队中的另一个指定 Agent 发送消息。接收方会在下一轮运行时看到该消息。"
                    + "使用 SendMessage 按名称与 teammate 沟通。消息会以 system reminder 的形式送达。";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("to", Map.of("type", "string", "description", "Name of the recipient agent"));
            props.put("content", Map.of("type", "string", "description", "Message content to send"));

            return Map.of(
                    "type", "object",
                    "properties", props,
                    "required", List.of("to", "content")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String to = (String) args.get("to");
            String content = (String) args.get("content");
            if (to == null || to.isEmpty() || content == null || content.isEmpty()) {
                return new ToolResult(false, "Error: 'to' and 'content' are required");
            }

            // Route to lead by finding any team the sender belongs to.
            if ("lead".equals(to)) {
                for (String teamName : teamManager.listTeams()) {
                    TeamManager.Team team = teamManager.getTeam(teamName);
                    if (team != null && team.hasMember(senderName)) {
                        team.sendMessage(senderName, "lead", content);
                        return new ToolResult(true, "Message sent to lead.");
                    }
                }
                return new ToolResult(false, "Error: cannot find team for sender '" + senderName + "'");
            }

            for (String teamName : teamManager.listTeams()) {
                TeamManager.Team team = teamManager.getTeam(teamName);
                if (team == null) continue;
                if (team.hasMember(to)) {
                    team.sendMessage(senderName, to, content);
                    return new ToolResult(true, "Message sent to " + to + ".");
                }
                // Fallback: sender belongs to this team but recipient not in Members
                // (tmux mode — each process only knows itself). Write to mailbox directly.
                if (team.hasMember(senderName)) {
                    team.sendMessage(senderName, to, content);
                    return new ToolResult(true, "Message sent to " + to + ".");
                }
            }

            return new ToolResult(false, "Error: recipient '" + to + "' not found in any team");
        }
    }

    // ── TeamCreate ─────────────────────────────────────────────────────

    public static class TeamCreateTool implements Tool {

        private final TeamManager teamManager;

        public TeamCreateTool(TeamManager teamManager) {
            this.teamManager = teamManager;
        }

        @Override public String getName() { return "TeamCreate"; }

        @Override
        public String getDescription() {
            return "创建一个新团队，用于协调多个 Agent 协作。\n\n"
                    + "## 何时使用\n\n"
                    + "在以下情况应主动使用此工具：\n"
                    + "- 用户明确要求使用 team、swarm 或一组 Agent\n"
                    + "- 用户提到希望多个 Agent 一起工作、协调或协作\n"
                    + "- 任务需要多个 Agent 串行或并行协作\n\n"
                    + "如果不确定任务是否值得创建团队，优先创建团队。\n\n"
                    + "## 团队工作流\n\n"
                    + "1. 使用 TeamCreate **创建团队**\n"
                    + "2. 使用 Agent 工具，并传入 team_name 和 name 参数来 **启动 teammate** —— "
                    + "这是创建长期运行团队成员的必要条件\n"
                    + "3. teammates 独立工作，并通过 **SendMessage** 沟通\n"
                    + "4. teammate 完成后，会通过 SendMessage 将结果发送给 \"lead\"，然后进入 idle 状态\n"
                    + "5. lead 收集并综合所有 teammate 的结果\n\n"
                    + "## 重要：启动 Teammate\n\n"
                    + "要向团队添加成员，必须同时向 Agent 工具传入 team_name 和 name：\n"
                    + "Agent(team_name=\"<team name>\", name=\"<member name>\", prompt=\"...\", description=\"...\")\n"
                    + "如果没有 team_name，该 Agent 会作为一次性 sub-agent 运行，阻塞并内联返回结果 —— "
                    + "它不会成为团队成员。\n\n"
                    + "## Teammate Idle 状态\n\n"
                    + "teammate 每轮结束后进入 idle 状态，这是完全正常的。"
                    + "向 idle teammate 发送消息会唤醒它。\n\n"
                    + "## 通信\n\n"
                    + "- 使用 SendMessage 按名称与 teammate 沟通\n"
                    + "- teammate 发来的消息会在每轮开始时以 system reminder 的形式送达\n"
                    + "- 消息会自动投递 —— 你不需要手动检查收件箱";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name for the team"));
            props.put("description", Map.of("type", "string", "description", "What this team will work on"));

            return Map.of(
                    "type", "object",
                    "properties", props,
                    "required", List.of("team_name")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return new ToolResult(false, "Error: team_name is required");
            }

            String baseName = name;
            for (int i = 2; teamManager.getTeam(name) != null; i++) {
                name = baseName + "-" + i;
            }

            TeamManager.TeamMode mode = TeamManager.detectBackend();
            TeamManager.Team team = teamManager.createTeam(name, mode);

            String desc = args.get("description") instanceof String s ? s : "";
            return new ToolResult(true,
                    "Team \"%s\" created (mode: %s). Use Agent tool with team_name=\"%s\" to add teammates.\nDescription: %s"
                            .formatted(team.getName(), team.getMode(), team.getName(), desc));
        }
    }

    // ── TeamDelete ─────────────────────────────────────────────────────

    public static class TeamDeleteTool implements Tool {
        private final TeamManager teamManager;

        public TeamDeleteTool(TeamManager teamMgr) {
            this.teamManager = teamMgr;
        }

        @Override public String getName() { return "TeamDelete"; }

        @Override
        public String getDescription() {
            return "删除一个团队，并停止其中的所有成员。";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name of the team to delete"));

            return Map.of(
                    "type", "object",
                    "properties", props,
                    "required", List.of("team_name")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return new ToolResult(false, "Error: team_name is required");
            }

            TeamManager.Team team = teamManager.getTeam(name);
            if (team == null) {
                return new ToolResult(false, "Error: team '%s' not found".formatted(name));
            }

            List<String> memberNames = team.memberNames();
            teamManager.deleteTeam(name);
            return new ToolResult(true,
                    "Team \"%s\" deleted. Stopped %d member(s): %s"
                            .formatted(name, memberNames.size(), String.join(", ", memberNames)));
        }
    }

    // ── TeamStopMember ─────────────────────────────────────────────────

    public static class TeamStopMemberTool implements Tool {
        private final TeamManager teamManager;

        public TeamStopMemberTool(TeamManager teamManager) {
            this.teamManager = teamManager;
        }

        @Override public String getName() { return "TeamStopMember"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String getDescription() {
            return "停止指定 team 中的单个 teammate。会发送 [shutdown] 消息并中断本进程线程；tmux 成员会额外 best-effort 停止 pane。";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Team name"));
            props.put("member_name", Map.of("type", "string", "description", "Member name to stop"));
            return Map.of("type", "object", "properties", props,
                    "required", List.of("team_name", "member_name"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String teamName = (String) args.get("team_name");
            String memberName = (String) args.get("member_name");
            if (teamName == null || teamName.isEmpty() || memberName == null || memberName.isEmpty()) {
                return new ToolResult(false, "Error: team_name and member_name are required");
            }
            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) {
                return new ToolResult(false, "Error: team '%s' not found".formatted(teamName));
            }
            TeamManager.Member member = team.getMember(memberName);
            if (member == null) {
                return new ToolResult(false, "Error: member '%s' not found in team '%s'".formatted(memberName, teamName));
            }
            team.sendMessage(TeammateRunner.LEAD_NAME, memberName, TeammateRunner.SHUTDOWN_PREFIX);
            team.stopMember(memberName);
            boolean stoppedPane = false;
            if (member.tmuxPaneId != null && !member.tmuxPaneId.isBlank()) {
                TmuxBackend.stopTmuxTeammate(member.tmuxPaneId);
                stoppedPane = true;
            }
            return new ToolResult(true,
                    "Stopped member '%s' in team '%s' (thread=%s, tmuxPane=%s)."
                            .formatted(memberName, teamName, member.thread != null, stoppedPane));
        }
    }

    // ── TeamMerge ──────────────────────────────────────────────────────

    public static class TeamMergeTool implements Tool {
        private final TeamManager teamManager;
        private final String workingDirectory;

        public TeamMergeTool(TeamManager teamManager, String workingDirectory) {
            this.teamManager = teamManager;
            this.workingDirectory = workingDirectory;
        }

        @Override public String getName() { return "TeamMerge"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String getDescription() {
            return "预览或确认合并 teammate 的 worktree 变更。默认只预览；只有 confirm=true 时才尝试合入主工作区。";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Team name"));
            props.put("member_name", Map.of("type", "string", "description", "Member whose worktree should be merged"));
            props.put("mode", Map.of("type", "string", "description", "preview or merge; defaults to preview"));
            props.put("confirm", Map.of("type", "boolean", "description", "Must be true to modify the main worktree"));
            return Map.of("type", "object", "properties", props,
                    "required", List.of("team_name", "member_name"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String teamName = (String) args.get("team_name");
            String memberName = (String) args.get("member_name");
            if (teamName == null || teamName.isEmpty() || memberName == null || memberName.isEmpty()) {
                return new ToolResult(false, "Error: team_name and member_name are required");
            }
            TeamManager.Team team = teamManager.getTeam(teamName);
            if (team == null) {
                return new ToolResult(false, "Error: team '%s' not found".formatted(teamName));
            }
            TeamManager.Member member = team.getMember(memberName);
            if (member == null) {
                return new ToolResult(false, "Error: member '%s' not found in team '%s'".formatted(memberName, teamName));
            }
            boolean confirm = Boolean.TRUE.equals(args.get("confirm"));
            TeamWorktreeMerge.MergeResult result = confirm
                    ? TeamWorktreeMerge.merge(member, workingDirectory)
                    : TeamWorktreeMerge.preview(member);
            return new ToolResult(result.success(), "[" + result.status() + "] " + result.message());
        }
    }
}

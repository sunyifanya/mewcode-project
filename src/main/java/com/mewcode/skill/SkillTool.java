package com.mewcode.skill;

import com.mewcode.skill.SkillCatalog.Skill;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolResult;

import java.util.*;
import java.util.function.Supplier;

/**
 * Built-in tool that activates a skill on demand.
 *
 * <p>The model calls this with a skill name and optional arguments.
 * The tool performs phase-2 loading (hot-reload from disk), validates
 * allowed_tools, and dispatches to either inline or fork execution.
 *
 * <p>This tool is SYSTEM-level — it always appears in the tool list
 * regardless of any active skill's allowed_tools whitelist.
 */
public class SkillTool implements Tool {

    private final SkillCatalog catalog;

    /** Provides the current agent's SkillHost (created per-turn). */
    private Supplier<SkillHost> hostSupplier;
    private Supplier<SkillForkHost> forkHostSupplier;

    public SkillTool(SkillCatalog catalog,
                     Supplier<SkillHost> hostSupplier,
                     Supplier<SkillForkHost> forkHostSupplier) {
        this.catalog = catalog;
        this.hostSupplier = hostSupplier;
        this.forkHostSupplier = forkHostSupplier;
    }

    /** Update host suppliers (called when a new agent is created). */
    public void setHostSuppliers(Supplier<SkillHost> hostSupplier,
                                  Supplier<SkillForkHost> forkHostSupplier) {
        this.hostSupplier = hostSupplier;
        this.forkHostSupplier = forkHostSupplier;
    }

    @Override
    public String getName() {
        return "Skill";
    }

    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("Activate a skill to get specialized instructions and optionally restricted tools. ");
        sb.append("Use this when a task matches an available skill's description — ");
        sb.append("the skill's SOP (standard operating procedure) will be loaded and pinned to your context.\n\n");
        sb.append("Available skills:\n");
        for (var meta : catalog.list()) {
            sb.append("- ").append(meta.name()).append(": ").append(meta.description());
            if (!meta.allowedTools().isEmpty()) {
                sb.append(" (tools: ").append(String.join(", ", meta.allowedTools())).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("skill", Map.of(
                "type", "string",
                "description", "The name of the skill to activate (e.g., 'review', 'commit', 'test')"
        ));
        properties.put("args", Map.of(
                "type", "string",
                "description", "Optional arguments to pass to the skill (replaces $ARGUMENTS in the SOP or appended to the end)"
        ));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("skill")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String skillName = stringVal(params, "skill");
        if (skillName == null || skillName.isBlank()) {
            return new ToolResult(false, "Skill name is required. Available skills: "
                    + String.join(", ", catalog.list().stream().map(SkillCatalog.SkillMeta::name).toList()));
        }

        // Phase 2: load full skill (hot reload from disk)
        Optional<Skill> optSkill = catalog.getFull(skillName);
        if (optSkill.isEmpty()) {
            return new ToolResult(false, "Unknown skill: '" + skillName
                    + "'. Available skills: "
                    + String.join(", ", catalog.list().stream().map(SkillCatalog.SkillMeta::name).toList()));
        }

        Skill skill = optSkill.get();
        String args = stringVal(params, "args");

        try {
            String mode = skill.meta().mode() != null ? skill.meta().mode() : "inline";
            if ("fork".equals(mode)) {
                SkillForkHost forkHost = forkHostSupplier != null ? forkHostSupplier.get() : null;
                if (forkHost == null) {
                    return new ToolResult(false, "Skill '" + skillName + "' requires fork mode but no fork host is available.");
                }
                String result = SkillExecutor.executeFork(skill, args, forkHost);
                return new ToolResult(true, result);
            } else {
                SkillHost host = hostSupplier != null ? hostSupplier.get() : null;
                if (host == null) {
                    return new ToolResult(false, "Skill '" + skillName + "' requires a host agent but none is available.");
                }
                String body = SkillExecutor.executeInline(skill, args, host);
                // Return a summary so the model knows the skill is loaded
                String preview = body.length() > 300 ? body.substring(0, 300) + "..." : body;
                return new ToolResult(true, "Skill '" + skillName + "' activated (inline mode).\n\nSOP preview:\n" + preview);
            }
        } catch (IllegalStateException e) {
            return new ToolResult(false, "Failed to activate skill '" + skillName + "': " + e.getMessage());
        }
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}

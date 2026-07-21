package com.mewcode.subagent;

import java.util.*;

/**
 * In-memory catalog of sub-agent definitions, with priority-based resolution.
 */
public class AgentCatalog {

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    public AgentCatalog() {}

    /**
     * Register a definition, overwriting any existing entry with the same name.
     */
    public void register(SubAgentSpec spec) {
        agents.put(spec.name(), spec);
    }

    /**
     * Bulk-register definitions from a map.
     */
    public void registerAll(Map<String, SubAgentSpec> specs) {
        agents.putAll(specs);
    }

    /**
     * Resolve a sub-agent type name to its highest-priority spec.
     *
     * @param name the subagent_type to look up
     * @return the spec, or null if not found
     */
    public SubAgentSpec resolve(String name) {
        return agents.get(name);
    }

    /**
     * @return sorted list of all registered agent names (excluding internal _fork)
     */
    public List<String> listNames() {
        var names = new ArrayList<String>();
        for (String name : agents.keySet()) {
            if (!name.startsWith("_")) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * @return the number of registered definitions
     */
    public int size() {
        return agents.size();
    }

    /**
     * @return unmodifiable view of all registered specs
     */
    public Map<String, SubAgentSpec> all() {
        return Collections.unmodifiableMap(agents);
    }
}

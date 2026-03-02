package com.bones.gateway.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> toolsByName;

    public AgentToolRegistry(List<AgentTool> tools) {
        this.toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.toolsByName.put(tool.name(), tool);
        }
    }

    public Optional<AgentTool> get(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public List<String> names() {
        return List.copyOf(toolsByName.keySet());
    }
}

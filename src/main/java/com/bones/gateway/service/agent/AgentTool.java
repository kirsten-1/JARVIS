package com.bones.gateway.service.agent;

import java.util.Map;

public interface AgentTool {

    String name();

    default String description() {
        return name();
    }

    default Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", true
        );
    }

    String buildInput(AgentToolContext context);

    String execute(AgentToolContext context);
}

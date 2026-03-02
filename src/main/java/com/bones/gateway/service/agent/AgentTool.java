package com.bones.gateway.service.agent;

public interface AgentTool {

    String name();

    String buildInput(AgentToolContext context);

    String execute(AgentToolContext context);
}

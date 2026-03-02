package com.bones.gateway.service.agent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimeNowAgentTool implements AgentTool {

    public static final String NAME = "time_now";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Get current timestamp in Asia/Shanghai timezone";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
    }

    @Override
    public String buildInput(AgentToolContext context) {
        return "{}";
    }

    @Override
    public String execute(AgentToolContext context) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return "current_time=" + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ", timezone=Asia/Shanghai";
    }
}

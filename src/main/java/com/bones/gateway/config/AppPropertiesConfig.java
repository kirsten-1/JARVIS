package com.bones.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        GuardProperties.class,
        ConversationContextProperties.class,
        BillingProperties.class,
        StreamSessionProperties.class,
        M6CacheProperties.class
})
public class AppPropertiesConfig {
}

package com.bones.gateway.integration.ai;

import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AiServiceClient {

    Mono<AiChatResponse> chat(AiChatRequest request);

    Flux<String> chatStream(AiChatRequest request);
}

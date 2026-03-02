package com.bones.gateway.integration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bones.gateway.common.BusinessException;
import com.bones.gateway.common.ErrorCode;
import com.bones.gateway.config.AiServiceProperties;
import com.bones.gateway.config.AiServiceProperties.ProviderProperties;
import com.bones.gateway.config.AiServiceProperties.ProviderProtocol;
import com.bones.gateway.integration.ai.model.AiChatRequest;
import com.bones.gateway.integration.ai.model.AiChatResponse;
import com.bones.gateway.service.OpsMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

class WebClientAiServiceClientTest {

    private MockWebServer mockWebServer;
    private WebClientAiServiceClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        client = createClient(basePropertiesFor(mockWebServer.url("/").toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void chat_shouldReturnAiChatResponse_whenStatusIs200() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "model":"mock-model",
                          "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20},
                          "choices":[
                            {
                              "message":{"role":"assistant","content":"hello"},
                              "finish_reason":"stop"
                            }
                          ]
                        }
                        """));

        AiChatResponse response = client.chat(new AiChatRequest("hi", 1L, 1L, null, null, null, null)).block();

        assertEquals("hello", response.content());
        assertEquals("mock-model", response.model());
        assertEquals("stop", response.finishReason());
        assertEquals(12, response.promptTokens());
        assertEquals(8, response.completionTokens());
        assertEquals(20, response.totalTokens());
    }

    @Test
    void chat_shouldParseToolCalls_whenOpenAiResponseContainsToolCalls() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "model":"gpt-4o-mini",
                          "choices":[
                            {
                              "message":{
                                "role":"assistant",
                                "tool_calls":[
                                  {
                                    "id":"call_123",
                                    "type":"function",
                                    "function":{
                                      "name":"time_now",
                                      "arguments":"{}"
                                    }
                                  }
                                ]
                              },
                              "finish_reason":"tool_calls"
                            }
                          ]
                        }
                        """));

        AiChatResponse response = client.chat(new AiChatRequest("hi", 1L, 1L, null, null, null, null)).block();

        assertEquals("tool_calls", response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call_123", response.toolCalls().get(0).id());
        assertEquals("time_now", response.toolCalls().get(0).name());
        assertEquals("{}", response.toolCalls().get(0).argumentsJson());
    }

    @Test
    void chat_shouldSendOpenAiToolsAndFilterControlMetadata() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "model":"mock-model",
                          "choices":[
                            {
                              "message":{"role":"assistant","content":"ok"},
                              "finish_reason":"stop"
                            }
                          ]
                        }
                        """));

        client.chat(new AiChatRequest(
                "hi",
                1L,
                1L,
                null,
                null,
                null,
                Map.of(
                        "openaiTools", List.of(
                                Map.of(
                                        "type", "function",
                                        "function", Map.of(
                                                "name", "time_now",
                                                "description", "time",
                                                "parameters", Map.of("type", "object", "properties", Map.of())
                                        )
                                )
                        ),
                        "openaiToolChoice", "auto",
                        "openaiParallelToolCalls", false,
                        "requestId", "abc123",
                        "allowedTools", List.of("time_now")
                )
        )).block();

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"tool_choice\":\"auto\""));
        assertTrue(body.contains("\"parallel_tool_calls\":false"));
        assertTrue(body.contains("\"metadata\":{\"requestId\":\"abc123\"}"));
    }

    @Test
    void chat_shouldThrowBusinessException_whenStatusIs500() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "text/plain")
                .setBody("internal error"));

        Throwable throwable = assertThrows(Throwable.class,
                () -> client.chat(new AiChatRequest("hi", 1L, 1L, null, null, null, null)).block());

        Throwable root = throwable.getCause() == null ? throwable : throwable.getCause();
        BusinessException businessException = assertInstanceOf(BusinessException.class, root);
        assertEquals(ErrorCode.AI_SERVICE_BAD_RESPONSE, businessException.getErrorCode());
    }

    @Test
    void chatStream_shouldNormalizeSseDataLines() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"first"}}]}

                        data: {"choices":[{"delta":{"content":"second"}}]}

                        data: [DONE]

                        """));

        List<String> chunks = client.chatStream(new AiChatRequest("hi", 1L, 1L, null, null, null, null))
                .collectList()
                .block();

        assertEquals(List.of("first", "second"), chunks);
    }

    @Test
    void chat_shouldRouteToSpecifiedProvider() throws Exception {
        try (MockWebServer glmServer = new MockWebServer()) {
            glmServer.start();
            glmServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                              "model":"glm-4-plus",
                              "choices":[{"message":{"content":"from-glm"},"finish_reason":"stop"}]
                            }
                            """));

            AiServiceProperties properties = basePropertiesFor(mockWebServer.url("/").toString());
            Map<String, ProviderProperties> providers = new LinkedHashMap<>();
            providers.put("local", openAiProvider(mockWebServer.url("/").toString(), "/v1/chat", "/v1/chat/stream", null, null));
            providers.put("glm", openAiProvider(glmServer.url("/").toString(), "/chat/completions", "/chat/completions", "glm-key", "glm-4-plus"));
            properties.setProviders(providers);
            properties.setDefaultProvider("local");

            WebClientAiServiceClient routingClient = createClient(properties);
            AiChatResponse response = routingClient.chat(new AiChatRequest("hello", 1L, 1L, "glm", null, null, null)).block();

            assertEquals("from-glm", response.content());
            assertTrue(glmServer.takeRequest().getPath().startsWith("/chat/completions"));
        }
    }

    @Test
    void chat_shouldFallbackToConfiguredProvider_whenPrimaryUnavailable() throws Exception {
        try (MockWebServer fallbackServer = new MockWebServer()) {
            fallbackServer.start();
            fallbackServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                              "model":"glm-4-plus",
                              "choices":[{"message":{"content":"from-fallback"},"finish_reason":"stop"}]
                            }
                            """));

            AiServiceProperties properties = new AiServiceProperties();
            properties.setConnectTimeoutMs(1000);
            properties.setReadTimeoutMs(5000);
            properties.setRetryEnabled(false);
            properties.setMaxRetries(0);
            properties.setRetryBackoffMs(100);
            properties.setDefaultProvider("deepseek");
            properties.setFallbackEnabled(true);
            properties.setFallbackProvider("glm");

            Map<String, ProviderProperties> providers = new LinkedHashMap<>();
            providers.put("deepseek", openAiProvider("http://127.0.0.1:19999", "/v1/chat/completions", "/v1/chat/completions", "deepseek-key", "deepseek-chat"));
            providers.put("glm", openAiProvider(fallbackServer.url("/").toString(), "/chat/completions", "/chat/completions", "glm-key", "glm-4-plus"));
            properties.setProviders(providers);

            WebClientAiServiceClient routingClient = createClient(properties);
            AiChatResponse response = routingClient.chat(new AiChatRequest("hello", 1L, 1L, "deepseek", null, null, null)).block();

            assertEquals("from-fallback", response.content());
            assertTrue(fallbackServer.takeRequest().getPath().startsWith("/chat/completions"));
        }
    }

    @Test
    void chat_shouldParseGeminiResponse_whenGeminiProvider() throws Exception {
        try (MockWebServer geminiServer = new MockWebServer()) {
            geminiServer.start();
            geminiServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                              "candidates":[
                                {
                                  "content":{"parts":[{"text":"gemini hello"}]},
                                  "finishReason":"STOP"
                                }
                              ],
                              "usageMetadata":{
                                "promptTokenCount":15,
                                "candidatesTokenCount":6,
                                "totalTokenCount":21
                              },
                              "modelVersion":"gemini-2.0-flash"
                            }
                            """));

            AiServiceProperties properties = new AiServiceProperties();
            properties.setConnectTimeoutMs(1000);
            properties.setReadTimeoutMs(5000);
            properties.setRetryEnabled(false);
            properties.setMaxRetries(0);
            properties.setRetryBackoffMs(100);
            properties.setDefaultProvider("gemini");

            ProviderProperties gemini = new ProviderProperties();
            gemini.setEnabled(true);
            gemini.setProtocol(ProviderProtocol.GEMINI);
            gemini.setBaseUrl(geminiServer.url("/").toString());
            gemini.setChatPath("/v1beta/models/{model}:generateContent");
            gemini.setStreamPath("/v1beta/models/{model}:streamGenerateContent?alt=sse");
            gemini.setApiKey("gemini-key");
            gemini.setApiKeyInQuery(true);
            gemini.setApiKeyQueryName("key");
            gemini.setModel("gemini-2.0-flash");

            properties.setProviders(Map.of("gemini", gemini));

            WebClientAiServiceClient routingClient = createClient(properties);
            AiChatResponse response = routingClient.chat(new AiChatRequest("hello", 1L, 1L, "gemini", "gemini-2.0-flash", null, null)).block();

            assertEquals("gemini hello", response.content());
            assertEquals("gemini-2.0-flash", response.model());
            assertEquals("STOP", response.finishReason());
            assertEquals(15, response.promptTokens());
            assertEquals(6, response.completionTokens());
            assertEquals(21, response.totalTokens());

            String path = geminiServer.takeRequest().getPath();
            assertTrue(path.startsWith("/v1beta/models/gemini-2.0-flash:generateContent"));
            assertTrue(path.contains("key=gemini-key"));
        }
    }

    @Test
    void chat_shouldParseAnthropicResponse_whenAnthropicProvider() throws Exception {
        try (MockWebServer anthropicServer = new MockWebServer()) {
            anthropicServer.start();
            anthropicServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                              "id":"msg_123",
                              "type":"message",
                              "model":"MiniMax-M1",
                              "role":"assistant",
                              "content":[{"type":"text","text":"hello minimaxi"}],
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":11,"output_tokens":7}
                            }
                            """));

            AiServiceProperties properties = new AiServiceProperties();
            properties.setConnectTimeoutMs(1000);
            properties.setReadTimeoutMs(5000);
            properties.setRetryEnabled(false);
            properties.setMaxRetries(0);
            properties.setRetryBackoffMs(100);
            properties.setDefaultProvider("minimax");

            ProviderProperties minimax = new ProviderProperties();
            minimax.setEnabled(true);
            minimax.setProtocol(ProviderProtocol.ANTHROPIC);
            minimax.setBaseUrl(anthropicServer.url("/").toString());
            minimax.setChatPath("/v1/messages");
            minimax.setStreamPath("/v1/messages");
            minimax.setApiKey("minimax-key");
            minimax.setApiKeyHeader("x-api-key");
            minimax.setApiKeyPrefix("");
            minimax.setModel("MiniMax-M1");
            minimax.setHeaders(Map.of("anthropic-version", "2023-06-01"));
            properties.setProviders(Map.of("minimax", minimax));

            WebClientAiServiceClient routingClient = createClient(properties);
            AiChatResponse response = routingClient.chat(new AiChatRequest("hello", 1L, 1L, "minimax", null, null, null)).block();

            assertEquals("hello minimaxi", response.content());
            assertEquals("MiniMax-M1", response.model());
            assertEquals("end_turn", response.finishReason());
            assertEquals(11, response.promptTokens());
            assertEquals(7, response.completionTokens());
            assertEquals(18, response.totalTokens());

            RecordedRequest recorded = anthropicServer.takeRequest();
            assertEquals("/v1/messages", recorded.getPath());
            assertEquals("minimax-key", recorded.getHeader("x-api-key"));
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"));
            assertTrue(recorded.getBody().readUtf8().contains("\"stream\":false"));
        }
    }

    private AiServiceProperties basePropertiesFor(String baseUrl) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl(baseUrl);
        properties.setChatPath("/v1/chat");
        properties.setStreamPath("/v1/chat/stream");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(5000);
        properties.setRetryEnabled(false);
        properties.setMaxRetries(0);
        properties.setRetryBackoffMs(100);
        properties.setDefaultProvider("local");
        return properties;
    }

    private ProviderProperties openAiProvider(String baseUrl,
                                              String chatPath,
                                              String streamPath,
                                              String apiKey,
                                              String model) {
        ProviderProperties provider = new ProviderProperties();
        provider.setEnabled(true);
        provider.setProtocol(ProviderProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl(baseUrl);
        provider.setChatPath(chatPath);
        provider.setStreamPath(streamPath);
        provider.setApiKey(apiKey);
        provider.setModel(model);
        return provider;
    }

    private WebClientAiServiceClient createClient(AiServiceProperties properties) {
        WebClient webClient = WebClient.builder()
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        return new WebClientAiServiceClient(webClient, properties, new SimpleMeterRegistry(), Mockito.mock(OpsMetricsService.class));
    }
}

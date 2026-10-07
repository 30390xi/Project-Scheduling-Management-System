package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.shared.contract.AiModelGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Loopback HTTP checks the actual production client, including auth and base URL paths. */
class AiProviderHttpTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "/v1", "/api/paas/v4/"})
    void sendsCompatibleRequestAndValidatesResponse(String basePath) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = AgentJson.toJson(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", AiSamples.VALID_SINGLE_LEAF,
                            "reasoning_content", "ignored"))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
            var properties = properties(Map.of("base-url", url, "thinking-mode", "disabled",
                    "max-output-tokens", "4096"));
            var gateway = new HttpAiModelGateway(url, properties.apiKey(), properties,
                    new PromptSanitizer(20_000));
            var result = gateway.analyze(new AiModelGateway.AnalysisRequest(UUID.randomUUID(),
                    UUID.randomUUID(), "实现示例登录接口", List.of("JAVA", "SQL")));
            assertThat(path.get()).isEqualTo(basePath.replaceAll("/+$", "") + "/chat/completions");
            assertThat(auth.get()).isEqualTo("Bearer test-only-secret");
            var request = AgentJson.toJsonNode(body.get());
            assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
            assertThat(request.path("stream").asBoolean()).isFalse();
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(new AiOutputValidator(properties).validate(result.structuredJson(),
                    List.of("JAVA", "SQL")).valid()).isTrue();
            assertThat(properties.toString()).doesNotContain("test-only-secret");
        } finally {
            server.stop(0);
        }
    }

    static AiGatewayProperties properties(Map<String, String> overrides) {
        var values = new java.util.HashMap<String, Object>();
        values.put("scheduler.agent.ai.provider", "openai-compatible");
        values.put("scheduler.agent.ai.api-key", "test-only-secret");
        values.put("scheduler.agent.ai.model", "test-model");
        overrides.forEach((key, value) -> values.put("scheduler.agent.ai." + key, value));
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("scheduler.agent.ai", Bindable.of(AiGatewayProperties.class)).get();
    }
}

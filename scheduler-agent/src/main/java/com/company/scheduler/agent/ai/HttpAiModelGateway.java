package com.company.scheduler.agent.ai;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.shared.contract.AiModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容 chat/completions 适配器。供应商可替换：换供应商就是换 baseUrl/model
 * 或提供另一个 AiModelGateway 实现，编排层不感知。
 * 超时由 HTTP 客户端控制；瞬时/永久错误在此分类，编排层决定重试与降级。
 */
public final class HttpAiModelGateway implements AiModelGateway {

    public record GatewayOutcome(AnalysisResult result, JsonNode usage) {}

    private final RestClient restClient;
    private final AiGatewayProperties properties;
    private final PromptSanitizer sanitizer;

    public HttpAiModelGateway(String baseUrl, String apiKey, AiGatewayProperties properties,
            PromptSanitizer sanitizer) {
        this(properties, sanitizer, buildRestClient(baseUrl, apiKey, properties));
    }

    /** 测试入口：注入 mock 的 RestClient（同包可见）。 */
    HttpAiModelGateway(AiGatewayProperties properties, PromptSanitizer sanitizer, RestClient restClient) {
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(String baseUrl, String apiKey,
            AiGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = properties.requestTimeout();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        return analyzeDetailed(request).result();
    }

    public GatewayOutcome analyzeDetailed(AnalysisRequest request) {
        return callModel(AiPrompts.SYSTEM_PROMPT,
                AiPrompts.userPrompt(sanitizer.sanitize(request.taskText()),
                        String.join(",", request.allowedSkillCodes())));
    }

    /** 结构修复调用：把上次输出与失败项发回，要求重新输出合法 JSON。 */
    public GatewayOutcome repairDetailed(AnalysisRequest request, String previousOutput, String violations) {
        return callModel(AiPrompts.REPAIR_SYSTEM_PROMPT,
                AiPrompts.repairUserPrompt(previousOutput, violations));
    }

    private GatewayOutcome callModel(String systemPrompt, String userPrompt) {
        String body = buildRequestBody(systemPrompt, userPrompt);
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403,
                            (req, res) -> {
                                throw new AiGatewayAuthException(
                                        "模型服务拒绝凭据（HTTP " + res.getStatusCode().value() + "）");
                            })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new AiGatewayTransientException("模型服务限流（HTTP 429）");
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new AiGatewayRejectedException(
                                "模型服务拒绝请求（HTTP " + res.getStatusCode().value() + "）");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new AiGatewayTransientException(
                                "模型服务暂时不可用（HTTP " + res.getStatusCode().value() + "）");
                    })
                    .body(String.class);
        } catch (AiGatewayAuthException | AiGatewayRejectedException | AiGatewayTransientException classified) {
            throw classified;
        } catch (RestClientResponseException responseError) {
            if (responseError.getStatusCode().value() == 429) {
                throw new AiGatewayTransientException("模型服务限流（HTTP 429）");
            }
            throw new AiGatewayTransientException("模型调用失败: " + responseError.getMessage());
        } catch (RuntimeException networkError) {
            // 包含 ResourceAccessException：连接失败/读超时
            throw new AiGatewayTransientException("模型网络错误: " + networkError.getMessage(), networkError);
        }
        return parseOutcome(responseBody);
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        String responseFormat = Boolean.TRUE.equals(properties.jsonMode())
                ? ",\"response_format\":{\"type\":\"json_object\"}" : "";
        // 提示与正文已转义为 JSON 字符串值；模型名等配置值由管理员配置
        return "{\"model\":" + jsonString(properties.model())
                + ",\"messages\":[{\"role\":\"system\",\"content\":"
                + jsonString(systemPrompt)
                + "},{\"role\":\"user\",\"content\":" + jsonString(userPrompt) + "}]"
                + responseFormat + "}";
    }

    private GatewayOutcome parseOutcome(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new AiGatewayTransientException("模型服务返回空响应体");
        }
        JsonNode root;
        try {
            root = AgentJson.toJsonNode(responseBody);
        } catch (RuntimeException parseError) {
            throw new AiGatewayTransientException("模型响应不是合法 JSON");
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new AiSchemaInvalidException("模型响应缺少 choices[0].message.content");
        }
        return new GatewayOutcome(
                new AnalysisResult(AiPrompts.SCHEMA_VERSION, stripCodeFence(content.asText()),
                        properties.model()),
                root.path("usage").isMissingNode() ? null : root.path("usage"));
    }

    /** 模型偶尔用 ```json 围栏包裹 JSON；剥掉围栏，内容原样交给校验器。 */
    static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String jsonString(String value) {
        return AgentJson.toJson(value);
    }
}

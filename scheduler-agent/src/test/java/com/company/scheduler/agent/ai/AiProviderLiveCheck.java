package com.company.scheduler.agent.ai;

import com.company.scheduler.shared.contract.AiModelGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/** Intentionally named *LiveCheck: never selected by ordinary test or integration verify. */
class AiProviderLiveCheck {
    @Test
    void oneSyntheticTaskThroughProductionGatewayAndValidator() throws Exception {
        if (!Boolean.getBoolean("ai.live")) {
            throw new AssertionError("真实调用必须通过 scripts/check-ai.sh --live 显式开启");
        }
        var environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load("app-config",
                new FileSystemResource(System.getenv("AI_LIVE_CONFIG_PATH")));
        sources.forEach(environment.getPropertySources()::addLast);
        var properties = Binder.get(environment)
                .bind("scheduler.agent.ai", Bindable.of(AiGatewayProperties.class)).get();
        if (!properties.gatewayConfigured()) {
            throw new AssertionError("模型配置不完整，未发送请求");
        }
        var gateway = new HttpAiModelGateway(properties.baseUrl(), properties.apiKey(), properties,
                new PromptSanitizer(properties.maxTaskTextChars()));
        long started = System.nanoTime();
        HttpAiModelGateway.GatewayOutcome result;
        try {
            result = gateway.analyzeDetailed(new AiModelGateway.AnalysisRequest(UUID.randomUUID(),
                    UUID.randomUUID(), """
                    合成测试任务：在已有 Java 21、Spring Boot 3 项目内实现分钟格式化纯函数。
                    输入非负整数分钟，输出小时数和余下分钟数；负数抛 IllegalArgumentException。
                    验收用例：0 -> 0小时0分钟，90 -> 1小时30分钟，-1 -> 异常。
                    只交付函数与单元测试，无数据库、接口、前端或部署改造。
                    """, List.of("JAVA")));
        } catch (AiGatewayAuthException error) {
            throw new AssertionError("鉴权失败：检查标准 API 密钥与权限（401/403）");
        } catch (AiGatewayRejectedException | AiGatewayTransientException | AiSchemaInvalidException error) {
            throw new AssertionError(error.getMessage());
        }
        var validation = new AiOutputValidator(properties).validate(result.result().structuredJson(), List.of("JAVA"));
        if (!validation.valid()) {
            throw new AssertionError("已连接模型，但结构/业务校验失败：" + validation.violations().stream()
                    .map(AiValidationResult.Violation::code).distinct().toList());
        }
        System.out.println("AI_LIVE_CHECK_OK elapsedMs=" + (System.nanoTime() - started) / 1_000_000
                + " totalTokens=" + (result.usage() == null ? "unknown"
                        : result.usage().path("total_tokens").asText("unknown"))
                + " subtasks=" + validation.output().subtasks().size());
    }
}

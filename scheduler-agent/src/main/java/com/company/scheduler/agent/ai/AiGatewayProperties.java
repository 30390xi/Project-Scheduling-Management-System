package com.company.scheduler.agent.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * scheduler.agent.ai.* 配置。密钥只从环境/秘密管理注入，不入库、不入日志。
 * provider=disabled（默认）或未配置 baseUrl/apiKey 时保持明确的关闭行为（503）。
 */
@ConfigurationProperties(prefix = "scheduler.agent.ai")
public record AiGatewayProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Duration requestTimeout,
        Integer maxProviderCallsPerRun,
        Duration runDeadline,
        Integer maxTaskTextChars,
        Boolean structureRepairEnabled,
        Boolean jsonMode,
        Double bufferRatio,
        Double autoAssignQualityThreshold,
        Double maxPessimisticOptimisticRatio,
        Integer maxLeafTaskMinutes,
        Integer maxSubtasks,
        Integer maxOutputTokens,
        String thinkingMode) {

    public static final String PROVIDER_DISABLED = "disabled";
    public static final String PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    public AiGatewayProperties {
        if (provider == null || provider.isBlank()) {
            provider = PROVIDER_DISABLED;
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            requestTimeout = Duration.ofSeconds(45);
        }
        if (maxProviderCallsPerRun == null || maxProviderCallsPerRun < 1) {
            maxProviderCallsPerRun = 4;
        }
        if (runDeadline == null || runDeadline.isNegative() || runDeadline.isZero()) {
            runDeadline = Duration.ofSeconds(180);
        }
        if (maxTaskTextChars == null || maxTaskTextChars < 200) {
            maxTaskTextChars = 20_000;
        }
        if (structureRepairEnabled == null) {
            structureRepairEnabled = true;
        }
        if (jsonMode == null) {
            jsonMode = true;
        }
        if (bufferRatio == null || bufferRatio < 0 || bufferRatio > 2) {
            bufferRatio = 0.20;
        }
        if (autoAssignQualityThreshold == null
                || autoAssignQualityThreshold < 0 || autoAssignQualityThreshold > 1) {
            autoAssignQualityThreshold = 0.80;
        }
        if (maxPessimisticOptimisticRatio == null || maxPessimisticOptimisticRatio < 1) {
            maxPessimisticOptimisticRatio = 2.5;
        }
        if (maxLeafTaskMinutes == null || maxLeafTaskMinutes < 30) {
            maxLeafTaskMinutes = 960;
        }
        if (maxSubtasks == null || maxSubtasks < 1) {
            maxSubtasks = 30;
        }
        if (maxOutputTokens == null) {
            maxOutputTokens = 8192;
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 32768) {
            throw new IllegalArgumentException("AI max-output-tokens 必须在 256..32768");
        }
        if (thinkingMode == null || thinkingMode.isBlank()) {
            thinkingMode = "provider-default";
        }
        if (!java.util.Set.of("provider-default", "enabled", "disabled").contains(thinkingMode)) {
            throw new IllegalArgumentException("AI thinking-mode 必须为 provider-default/enabled/disabled");
        }
    }

    /** 仅供测试/默认场景使用的全默认实例；不能作为构造器，否则构造器绑定失效。 */
    public static AiGatewayProperties defaults() {
        return new AiGatewayProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "AiGatewayProperties[provider=" + provider + ", credentials=REDACTED]";
    }

    /** 未配置或显式 disabled 都视为关闭；关闭不是错误，是明确降级。 */
    public boolean gatewayConfigured() {
        return PROVIDER_OPENAI_COMPATIBLE.equals(provider)
                && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}

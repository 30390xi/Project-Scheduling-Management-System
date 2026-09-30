package com.company.scheduler.agent.ai;

import com.company.scheduler.shared.contract.AiModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型网关装配。scheduler.agent.ai.provider 未配置或为 disabled 时，
 * 只注册明确失败的关闭适配器（503 AI_NOT_CONFIGURED），绝不返回伪造估算。
 * 测试可通过 @ConditionalOnMissingBean 覆盖为自己的 mock 网关。
 */
@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiConfiguration {

    @Bean
    public PromptSanitizer promptSanitizer(AiGatewayProperties properties) {
        return new PromptSanitizer(properties.maxTaskTextChars());
    }

    @Bean
    @ConditionalOnMissingBean(AiModelGateway.class)
    public AiModelGateway aiModelGateway(AiGatewayProperties properties, PromptSanitizer sanitizer) {
        if (properties.gatewayConfigured()) {
            return new HttpAiModelGateway(properties.baseUrl(), properties.apiKey(), properties, sanitizer);
        }
        return new DisabledAiModelGateway();
    }
}

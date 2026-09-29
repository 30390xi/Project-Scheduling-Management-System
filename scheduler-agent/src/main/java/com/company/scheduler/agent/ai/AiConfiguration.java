package com.company.scheduler.agent.ai;

import com.company.scheduler.shared.contract.AiModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiModelGateway.class)
    AiModelGateway disabledAiModelGateway() { return new DisabledAiModelGateway(); }
}

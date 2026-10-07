package com.company.scheduler;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.ai.AiGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class AiEnvironmentConfigurationTest {
    @Test
    void bindsDocumentedEnvironmentVariablesThroughActualAppYaml() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("AI_PROVIDER", "openai-compatible")
                .withProperty("AI_BASE_URL", "https://api.deepseek.com")
                .withProperty("AI_MODEL", "test-model")
                .withProperty("AI_API_KEY", "test-only-secret")
                .withProperty("AI_THINKING_MODE", "disabled")
                .withProperty("AI_MAX_OUTPUT_TOKENS", "4096");
        var properties = bind(environment);
        assertThat(properties.gatewayConfigured()).isTrue();
        assertThat(properties.apiKey()).isEqualTo("test-only-secret");
        assertThat(properties.thinkingMode()).isEqualTo("disabled");
        assertThat(properties.maxOutputTokens()).isEqualTo(4096);
        assertThat(properties.requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
    }

    @Test
    void noEnvironmentKeepsGatewayDisabled() throws Exception {
        assertThat(bind(new MockEnvironment()).gatewayConfigured()).isFalse();
    }

    private AiGatewayProperties bind(MockEnvironment environment) throws Exception {
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("scheduler.agent.ai", Bindable.of(AiGatewayProperties.class)).get();
    }
}

package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;
import com.company.scheduler.shared.contract.AiModelGateway;
import com.company.scheduler.shared.error.BusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisabledAiModelGatewayTest {
    @Test
    void doesNotInventResultsWhenNoProviderIsConfigured() {
        var request = new AiModelGateway.AnalysisRequest(UUID.randomUUID(), UUID.randomUUID(), "需求", List.of("JAVA"));
        assertThatThrownBy(() -> new DisabledAiModelGateway().analyze(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo("AI_NOT_CONFIGURED"));
    }
}

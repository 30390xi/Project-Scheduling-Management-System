package com.company.scheduler.agent.ai;

import com.company.scheduler.shared.contract.AiModelGateway;
import com.company.scheduler.shared.error.BusinessException;
import org.springframework.http.HttpStatus;

/** Explicit failure is preferable to returning invented AI estimates. Replace via a configured adapter. */
public class DisabledAiModelGateway implements AiModelGateway {
    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "尚未配置 AI 模型服务，请使用人工估算");
    }
}

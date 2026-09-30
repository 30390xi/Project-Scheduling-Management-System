package com.company.scheduler.agent.ai;

import com.company.scheduler.shared.contract.AiModelGateway;
import com.company.scheduler.shared.error.BusinessException;
import org.springframework.http.HttpStatus;

/** 未配置模型时的明确关闭行为：调用直接失败（503），绝不返回编造的估算。 */
public class DisabledAiModelGateway implements AiModelGateway {

    public static final String MODEL = "none";

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                "尚未配置 AI 模型服务，请使用人工估算");
    }
}

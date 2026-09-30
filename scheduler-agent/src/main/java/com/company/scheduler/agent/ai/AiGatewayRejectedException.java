package com.company.scheduler.agent.ai;

/** 供应商拒绝请求（400/404/422 等）：请求构造或配额问题，重试无意义。 */
public class AiGatewayRejectedException extends RuntimeException {
    public AiGatewayRejectedException(String message) { super(message); }
}

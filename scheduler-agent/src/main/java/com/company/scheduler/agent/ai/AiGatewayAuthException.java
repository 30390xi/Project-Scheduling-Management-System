package com.company.scheduler.agent.ai;

/** 凭据/权限类错误：重试无意义，直接失败并通知。 */
public class AiGatewayAuthException extends RuntimeException {
    public AiGatewayAuthException(String message) { super(message); }
}

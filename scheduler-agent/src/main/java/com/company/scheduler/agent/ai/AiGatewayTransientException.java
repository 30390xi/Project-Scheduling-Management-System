package com.company.scheduler.agent.ai;

/** 瞬时故障（网络、429、5xx）：作业层可在预算内重试。 */
public class AiGatewayTransientException extends RuntimeException {
    public AiGatewayTransientException(String message) { super(message); }
    public AiGatewayTransientException(String message, Throwable cause) { super(message, cause); }
}

package com.company.scheduler.agent.ai;

/** 供应商返回了无法解析为结构化输出的内容：允许一次结构修复，仍失败则明确失败。 */
public class AiSchemaInvalidException extends RuntimeException {
    public AiSchemaInvalidException(String message) { super(message); }
}

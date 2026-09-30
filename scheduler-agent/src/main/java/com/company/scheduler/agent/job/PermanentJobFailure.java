package com.company.scheduler.agent.job;

/** 永久失败：重试不可能成功（如凭据无效、输入非法）。直接置 FAILED。 */
public class PermanentJobFailure extends RuntimeException {
    private final String errorCode;

    public PermanentJobFailure(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PermanentJobFailure(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}

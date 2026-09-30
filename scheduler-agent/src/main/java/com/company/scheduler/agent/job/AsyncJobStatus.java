package com.company.scheduler.agent.job;

/** 状态机：QUEUED → RUNNING → SUCCEEDED / RETRY_WAIT / FAILED / CANCELED。 */
public enum AsyncJobStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}

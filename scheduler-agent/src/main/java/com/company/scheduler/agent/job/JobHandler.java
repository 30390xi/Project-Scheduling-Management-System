package com.company.scheduler.agent.job;

/** 作业处理器。实现方必须可重入：同一作业可能因租约回收被再次执行。 */
public interface JobHandler {

    String jobType();

    /**
     * 执行作业。产生业务副作用前必须调用 JobRepository.renewLease 做 fencing 检查，
     * 确认租约仍属于自己，防止旧工作者覆盖回收后的新结果。
     */
    void handle(ClaimedJob job) throws Exception;
}

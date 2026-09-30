package com.company.scheduler.agent.job;

/** D 模块拥有的作业类型。其他模块经由 JobDispatcher 契约入队。 */
public final class JobTypes {
    /** 单任务 AI 分析：分类、三点工时估算、必要时的拆解建议。 */
    public static final String AI_ANALYSIS = "AI_ANALYSIS";

    private JobTypes() {}
}

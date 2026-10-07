import type { Analysis, AnalysisOutput } from "./types";
export const statuses: Record<string, string> = {
    DRAFT: "草稿",
    SUBMITTED: "待分析",
    NEEDS_INFO: "待补充",
    READY: "待开始",
    IN_PROGRESS: "进行中",
    BLOCKED: "已阻塞",
    IN_REVIEW: "待验收",
    DONE: "已完成",
    CANCELED: "已取消",
    UNSCHEDULED: "未排期",
    PLANNED: "已排期",
    STALE: "需重新排期",
    CLOSED: "已关闭",
    PENDING: "等待分析",
    RUNNING: "分析中",
    SUCCEEDED: "分析完成",
    REUSED: "复用结果",
    FAILED: "分析失败",
    GENERATING: "生成中",
    INFEASIBLE: "存在冲突",
    COMMITTED: "已提交",
    ACTIVE: "在职",
    INACTIVE: "停用",
    ADMIN: "管理员",
    MANAGER: "经理",
    MEMBER: "成员",
    OWNER: "项目负责人",
};
export const label = (status: string) => statuses[status] || status;
export function minutes(value: number | null | undefined) {
    if (value == null) return "待估算";
    const h = Math.floor(value / 60),
        m = value % 60;
    return h ? `${h}小时${m ? m + "分" : ""}` : `${m}分钟`;
}
export function dateString(date = new Date()) {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
export function addDays(day: string, count: number) {
    const d = new Date(day + "T12:00:00");
    d.setDate(d.getDate() + count);
    return dateString(d);
}
export function weekStart(day = dateString()) {
    const d = new Date(day + "T12:00:00");
    return addDays(day, -((d.getDay() + 6) % 7));
}
export function analysisOutput(
    run: Analysis | undefined,
): AnalysisOutput | null {
    try {
        return run?.validatedOutputJson
            ? JSON.parse(run.validatedOutputJson).output || null
            : null;
    } catch {
        return null;
    }
}
export function uid() {
    return crypto.randomUUID();
}

export function taskNumber(value: number | null | undefined) {
    return value == null ? "草稿未编号" : "#" + String(value).padStart(4, "0");
}

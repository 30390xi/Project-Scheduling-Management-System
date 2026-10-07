import { test, expect, type Page } from "@playwright/test";
const task = {
    id: "task-1",
    taskNo: 1,
    projectId: "project-1",
    parentId: null,
    nodeType: "LEAF",
    title: "合成任务 · 订单服务",
    description: "仅供界面测试的合成任务",
    acceptanceCriteria: "单元测试通过",
    status: "SUBMITTED",
    scheduleStatus: "UNSCHEDULED",
    priority: "P2",
    version: 2,
    estimatedMinutes: null,
    remainingMinutes: null,
    actualMinutes: 0,
    skills: [],
    children: [],
    predecessors: [],
    events: [],
    workLogs: [],
};
async function fixture(page: Page, status = "READY") {
    const calls: {
        url: string;
        body: unknown;
        headers: Record<string, string>;
    }[] = [];
    await page.route("**/api/v1/**", async (route) => {
        const req = route.request(),
            url = new URL(req.url()),
            path = url.pathname.replace("/api/v1", "");
        const respond = (data: unknown) => route.fulfill({ json: { data } });
        if (req.method() !== "GET")
            calls.push({
                url: path,
                body: req.postDataJSON(),
                headers: req.headers(),
            });
        if (path === "/auth/csrf")
            return respond({
                headerName: "X-CSRF-TOKEN",
                token: "fixture-token",
            });
        if (path === "/me") return respond({ userId: "user-1" });
        if (path === "/me/companies")
            return respond([
                {
                    id: "company-1",
                    name: "合成测试公司",
                    timezone: "Asia/Shanghai",
                    role: "ADMIN",
                },
            ]);
        if (path === "/projects")
            return respond([{ id: "project-1", name: "合成测试项目" }]);
        if (path === "/projects/project-1/members")
            return respond([
                {
                    memberId: "member-1",
                    username: "测试成员",
                    role: "OWNER",
                    resourcePermission: true,
                },
            ]);
        if (path === "/members")
            return respond([
                {
                    memberId: "member-1",
                    userId: "user-1",
                    username: "测试成员",
                    role: "ADMIN",
                    status: "ACTIVE",
                    version: 0,
                },
            ]);
        if (path === "/skills") return respond([]);
        if (path === "/tasks/task-1") return respond(task);
        if (path === "/tasks/task-1/ai-analysis-runs")
            return respond([
                {
                    id: "run-1",
                    status: "SUCCEEDED",
                    model: "fixture-model",
                    createdAt: "2026-10-07T01:00:00Z",
                    qualityScore: 0.9,
                    validatedOutputJson: JSON.stringify({
                        output: {
                            summary: "订单服务开发与验证",
                            estimate: {
                                optimisticMinutes: 60,
                                likelyMinutes: 90,
                                pessimisticMinutes: 120,
                                basis: "明确的业务边界",
                            },
                            requiredSkills: [],
                            acceptanceCriteria: ["测试通过"],
                            assumptions: [
                                {
                                    text: "现有账号接口可复用",
                                    confirmed: false,
                                },
                            ],
                            missingInformation: [],
                            subtasks: [],
                            dependencies: [],
                        },
                    }),
                },
            ]);
        if (path === "/ai-analysis-runs/run-1/confirm")
            return respond({ taskIds: ["task-1"], simulationJobId: "job-1" });
        if (path === "/schedule-simulations/job-1")
            return respond({
                id: "plan-1",
                jobId: "job-1",
                status,
                version: 1,
                expiresAt: "2100-01-01T00:00:00Z",
                output: {
                    tasks: [{ id: "task-1", title: task.title }],
                    fragments:
                        status === "READY"
                            ? [
                                  {
                                      taskId: "task-1",
                                      memberId: "member-1",
                                      date: "2026-10-07",
                                      minutes: 90,
                                  },
                              ]
                            : [],
                    conflicts:
                        status === "INFEASIBLE"
                            ? [
                                  {
                                      taskId: "task-1",
                                      code: "NO_CAPACITY",
                                      message: "合格成员没有空闲容量",
                                  },
                              ]
                            : [],
                },
            });
        if (path === "/schedule-plans/plan-1/commit")
            return route.fulfill({
                status: 409,
                json: {
                    code: "PLAN_STALE",
                    message: "方案过期或输入已变更，请重新模拟",
                },
            });
        if (
            path.endsWith("/capacity") ||
            path.startsWith("/schedules/members/")
        )
            return respond([]);
        return route.fulfill({
            status: 404,
            json: { code: "NOT_FOUND", message: "fixture endpoint missing" },
        });
    });
    return calls;
}
test("AI 建议必须明确勾选并二次确认，过期方案错误可见", async ({ page }) => {
    const calls = await fixture(page);
    await page.goto("/#/tasks/task-1");
    await page.getByRole("tab", { name: "AI 分析建议" }).click();
    await expect(
        page.getByText("现有账号接口可复用", { exact: true }),
    ).toBeVisible();
    const confirm = page.getByRole("button", { name: "确认建议并模拟排期" });
    await expect(confirm).toBeDisabled();
    expect(calls.length).toBe(0);
    await page.getByLabel("我已核对建议，并确认上述关键假设").check();
    await expect(confirm).toBeEnabled();
    await confirm.click();
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(
        page.getByRole("heading", { name: "排期方案", exact: true }),
    ).toBeVisible();
    expect(calls[0]?.body).toEqual({ confirmAllAssumptions: true });
    expect(calls[0]?.headers["x-csrf-token"]).toBe("fixture-token");
    expect(calls[0]?.headers["x-company-id"]).toBe("company-1");
    await page.getByRole("button", { name: "提交排期", exact: true }).click();
    await page.getByRole("button", { name: "确认提交", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("方案过期或输入已变更");
    expect(calls.filter((c) => c.url.endsWith("/commit"))).toHaveLength(1);
});
test("容量冲突展示且不可提交", async ({ page }) => {
    await fixture(page, "INFEASIBLE");
    await page.goto("/#/schedule?job=job-1");
    await expect(page.getByText("合格成员没有空闲容量")).toBeVisible();
    await expect(
        page.getByRole("button", { name: "提交排期", exact: true }),
    ).toHaveCount(0);
});

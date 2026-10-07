import { test, expect, type Page } from "@playwright/test";
import { readFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
// Local only. Never attach request traces or credentials to test reports.
const env = Object.fromEntries(
    readFileSync(resolve("..", ".env"), "utf8")
        .split("\n")
        .filter((l) => l && !l.startsWith("#") && l.includes("="))
        .map((l) => {
            const i = l.indexOf("=");
            return [l.slice(0, i), l.slice(i + 1).replace(/^['"]|['"]$/g, "")];
        }),
);
const username = process.env.E2E_USERNAME || env.BOOTSTRAP_USERNAME;
const password = process.env.E2E_PASSWORD || env.BOOTSTRAP_PASSWORD;
const artifactDir = resolve("..", ".runtime", "frontend");
mkdirSync(artifactDir, { recursive: true });
async function login(page: Page) {
    await page.goto("/");
    await page.getByLabel("账号", { exact: true }).fill(username!);
    await page.getByLabel("密码", { exact: true }).fill(password!);
    await page.getByRole("button", { name: "登录工作空间" }).click();
    await expect(
        page.getByRole("heading", { name: "工作概览." }),
    ).toBeVisible();
}

test("真实后端：登录、会话恢复、建项目、创建编辑删除草稿、周排期与退出", async ({
    page,
}) => {
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await login(page);
    await page.screenshot({
        path: resolve(artifactDir, "dashboard-desktop.png"),
        fullPage: true,
        animations: "disabled",
    });
    // Current identity comes from GET /me, not from sessionStorage.
    await page.evaluate(() => sessionStorage.removeItem("scheduler.user"));
    await page.reload();
    await expect(
        page.getByRole("heading", { name: "工作概览." }),
    ).toBeVisible();
    await page.getByRole("link", { name: "项目设置", exact: true }).click();
    await page
        .getByRole("button", { name: "创建项目", exact: true })
        .first()
        .click();
    const projectName = "前端联调验收 " + Date.now();
    await page.getByLabel("项目名称").fill(projectName);
    await page.getByRole("button", { name: "确认保存" }).click();
    await expect(
        page.getByRole("heading", { name: projectName, exact: true }),
    ).toBeVisible();
    await page.getByRole("link", { name: "任务中心", exact: true }).click();
    await page.getByRole("button", { name: "创建任务", exact: true }).click();
    await page.getByLabel("任务名称").fill("前端验收 · 分钟格式化函数");
    await page
        .getByLabel("需求描述")
        .fill(
            "合成验收数据：实现分钟格式化纯函数。90 分钟输出 1 小时 30 分钟，不包含数据库改造。",
        );
    await page.getByLabel("人工预估工时").fill("90");
    await page
        .getByLabel("验收标准")
        .fill("0 和 90 分钟转换正确，负数返回错误。");
    await page.getByRole("button", { name: "保存任务", exact: true }).click();
    await expect(
        page.getByRole("heading", {
            name: "前端验收 · 分钟格式化函数",
            exact: true,
        }),
    ).toBeVisible();
    await page.getByRole("button", { name: "编辑任务", exact: true }).click();
    await page
        .getByLabel("任务名称")
        .fill("前端验收 · 分钟格式化函数（已编辑）");
    await page.getByRole("button", { name: "保存任务", exact: true }).click();
    await expect(
        page.getByRole("heading", {
            name: "前端验收 · 分钟格式化函数（已编辑）",
            exact: true,
        }),
    ).toBeVisible();
    await page.screenshot({
        path: resolve(artifactDir, "task-detail.png"),
        fullPage: true,
        animations: "disabled",
    });
    await page.getByRole("tab", { name: "AI 分析建议" }).click();
    await expect(
        page.getByRole("heading", { name: "尚未生成分析建议" }),
    ).toBeVisible();
    await page.getByRole("tab", { name: "任务详情" }).click();
    await page.getByRole("button", { name: "删除草稿", exact: true }).click();
    await page.getByRole("button", { name: "确认操作" }).click();
    await expect(
        page.getByRole("heading", { name: "任务中心." }),
    ).toBeVisible();
    await page.getByRole("link", { name: "成员排期", exact: true }).click();
    await expect(page.locator(".calendar-table")).toBeVisible();
    await expect(page.getByText("每日负载", { exact: false })).toBeVisible();
    await page.getByRole("button", { name: "下一周", exact: true }).click();
    await expect(page.locator(".calendar-table")).toBeVisible();
    await page.screenshot({
        path: resolve(artifactDir, "schedule-desktop.png"),
        fullPage: true,
        animations: "disabled",
    });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("button", { name: "打开导航" }).click();
    await page.getByRole("link", { name: "工作概览", exact: true }).click();
    await expect(
        page.getByRole("heading", { name: "工作概览." }),
    ).toBeVisible();
    expect(
        await page.evaluate(
            () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
    ).toBe(true);
    await page.screenshot({
        path: resolve(artifactDir, "dashboard-mobile.png"),
        fullPage: true,
        animations: "disabled",
    });
    await page.getByRole("button", { name: "打开导航" }).click();
    await page.getByRole("button", { name: "退出登录" }).click();
    await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
    expect(errors).toEqual([]);
});

test("真实后端：技能目录、成员创建、认证与容量、首次改密", async ({
    page,
    browser,
}) => {
    await login(page);
    await page.getByRole("link", { name: "团队与技能", exact: true }).click();
    const suffix = Date.now();
    const skillName = "前端验收技能 " + suffix,
        memberName = "ui_check_" + suffix;
    await page.getByRole("button", { name: /技能目录/ }).click();
    await page
        .getByRole("button", { name: "新增技能", exact: true })
        .first()
        .click();
    await page.getByLabel("技能名称").fill(skillName);
    await page.getByLabel("技能代码").fill("UI_" + suffix);
    await page.getByRole("button", { name: "确认保存" }).click();
    await expect(page.getByText(skillName, { exact: true })).toBeVisible();
    await page.getByRole("button", { name: /团队成员/ }).click();
    await page.getByRole("button", { name: "添加成员", exact: true }).click();
    await page.getByLabel("账号名称").fill(memberName);
    await page.getByRole("button", { name: "确认保存" }).click();
    await expect(
        page.getByRole("heading", { name: "成员账号已创建" }),
    ).toBeVisible();
    const temporaryPassword = await page
        .locator(".credential code")
        .textContent();
    await page.getByRole("button", { name: "已记录，关闭" }).click();
    const card = page
        .locator(".member-card")
        .filter({
            has: page.getByRole("heading", { name: memberName, exact: true }),
        });
    await card.getByRole("button", { name: "技能与容量" }).click();
    await page.getByLabel("每日任务上限（分钟）").fill("300");
    await page.getByRole("button", { name: "保存容量", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("每日容量已更新");
    await page
        .locator(".skill-setting")
        .filter({ hasText: skillName })
        .locator("select")
        .selectOption("3");
    await page.getByRole("button", { name: "保存认证技能" }).click();
    await expect(page.getByRole("status")).toContainText("认证技能已更新");
    await page.getByRole("button", { name: "关闭弹窗" }).click();
    await expect(card).toContainText("5小时");
    await page.getByRole("link", { name: "项目设置", exact: true }).click();
    await page.getByRole("button", { name: "添加成员", exact: true }).click();
    await page
        .getByRole("combobox", { name: "成员", exact: true })
        .selectOption({ label: memberName });
    await page.getByRole("button", { name: "确认保存" }).click();
    await expect(
        page.getByRole("cell", { name: new RegExp(memberName) }),
    ).toBeVisible();
    const context = await browser.newContext();
    const member = await context.newPage();
    await member.goto("/");
    await member.getByLabel("账号", { exact: true }).fill(memberName);
    await member.getByLabel("密码", { exact: true }).fill(temporaryPassword!);
    await member.getByRole("button", { name: "登录工作空间" }).click();
    await expect(
        member.getByRole("heading", { name: "设置你的新密码" }),
    ).toBeVisible();
    await member.getByLabel("当前 / 临时密码").fill(temporaryPassword!);
    await member
        .getByLabel("新密码", { exact: true })
        .fill("Ui-test-only-Change-123!");
    await member.getByLabel("确认新密码").fill("Ui-test-only-Change-123!");
    await member.getByRole("button", { name: "保存新密码" }).click();
    await expect(
        member.getByRole("heading", { name: "工作概览." }),
    ).toBeVisible();
    await member.getByRole("link", { name: "团队与技能", exact: true }).click();
    await expect(
        member.getByRole("button", { name: "添加成员", exact: true }),
    ).toHaveCount(0);
    await member.getByRole("link", { name: "成员排期", exact: true }).click();
    await expect(member.locator(".calendar-table tbody tr")).toHaveCount(1);
    await context.close();
    // Disable this clearly labelled test account after the read/write acceptance checks.
    await page.getByRole("link", { name: "团队与技能", exact: true }).click();
    const cleanup = page
        .locator(".member-card")
        .filter({
            has: page.getByRole("heading", { name: memberName, exact: true }),
        });
    await cleanup.getByRole("button", { name: "停用", exact: true }).click();
    await page.getByLabel("变更原因").fill("前端联调验收结束，停用测试账号");
    await page.getByRole("button", { name: "确认保存" }).click();
    await expect(cleanup).toContainText("停用");
});

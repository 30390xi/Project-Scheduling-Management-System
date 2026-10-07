import { describe, it, expect, vi, afterEach } from "vitest";
import { api, setApiCompany, query } from "../../src/api";
import { analysisOutput, addDays, minutes, weekStart } from "../../src/utils";
import type { Analysis } from "../../src/types";
afterEach(() => {
    vi.unstubAllGlobals();
    setApiCompany("");
});
describe("Authenticated transport", () => {
    it("sends fresh CSRF, company, version and idempotency headers on a write", async () => {
        const fetch = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        data: {
                            headerName: "X-CSRF-TOKEN",
                            token: "fake-csrf",
                        },
                    }),
                ),
            )
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ data: { id: "task-1" } })),
            );
        vi.stubGlobal("fetch", fetch);
        setApiCompany("company-1");
        await expect(
            api("/tasks/task-1", {
                method: "PATCH",
                body: { title: "新任务" },
                version: 7,
                key: "operation-1",
            }),
        ).resolves.toEqual({ id: "task-1" });
        expect(fetch.mock.calls[0][0]).toBe("/api/v1/auth/csrf");
        expect(fetch.mock.calls[1][1]).toMatchObject({
            method: "PATCH",
            credentials: "same-origin",
            headers: {
                "X-CSRF-TOKEN": "fake-csrf",
                "X-Company-Id": "company-1",
                "If-Match": "7",
                "Idempotency-Key": "operation-1",
            },
            body: '{"title":"新任务"}',
        });
    });
    it("does not retry a rejected write and preserves server error information", async () => {
        const fetch = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        data: { headerName: "X-CSRF-TOKEN", token: "fake" },
                    }),
                ),
            )
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        code: "PLAN_STALE",
                        message: "方案已过期",
                        requestId: "r-1",
                    }),
                    { status: 409 },
                ),
            );
        vi.stubGlobal("fetch", fetch);
        await expect(
            api("/schedule-plans/a/commit", {
                method: "POST",
                body: { version: 1 },
            }),
        ).rejects.toMatchObject({
            code: "PLAN_STALE",
            message: "方案已过期",
            requestId: "r-1",
        });
        expect(fetch).toHaveBeenCalledTimes(2);
    });
    it("signals session expiry without treating server errors as empty successful data", async () => {
        const dispatchEvent = vi.fn();
        vi.stubGlobal("window", { dispatchEvent });
        vi.stubGlobal(
            "fetch",
            vi
                .fn()
                .mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            code: "UNAUTHENTICATED",
                            message: "请先登录",
                        }),
                        { status: 401 },
                    ),
                ),
        );
        await expect(api("/me")).rejects.toMatchObject({ status: 401 });
        expect(dispatchEvent.mock.calls[0][0].type).toBe("session-expired");
    });
    it("routes forced password changes to the password screen", async () => {
        const dispatchEvent = vi.fn();
        vi.stubGlobal("window", { dispatchEvent });
        vi.stubGlobal(
            "fetch",
            vi
                .fn()
                .mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            code: "PASSWORD_CHANGE_REQUIRED",
                            message: "请改密",
                        }),
                        { status: 403 },
                    ),
                ),
        );
        await expect(api("/me")).rejects.toMatchObject({
            code: "PASSWORD_CHANGE_REQUIRED",
        });
        expect(dispatchEvent.mock.calls[0][0].type).toBe("password-required");
    });
    it("encodes company-independent query values and excludes absent filters", () => {
        expect(
            query({ status: "", projectId: null, search: "A&B", limit: 50 }),
        ).toBe("?search=A%26B&limit=50");
    });
});
describe("Presentation conversions", () => {
    it("handles month boundaries and uses local calendar dates", () => {
        expect(addDays("2026-10-31", 1)).toBe("2026-11-01");
        expect(weekStart("2026-10-07")).toBe("2026-10-05");
    });
    it("does not treat missing effort as zero", () => {
        expect(minutes(null)).toBe("待估算");
        expect(minutes(0)).toBe("0分钟");
        expect(minutes(90)).toBe("1小时30分");
    });
    it("only displays validated model output", () => {
        expect(
            analysisOutput({ validatedOutputJson: "broken" } as Analysis),
        ).toBeNull();
        expect(
            analysisOutput({
                validatedOutputJson: '{"output":{"summary":"ok"}}',
            } as Analysis)?.summary,
        ).toBe("ok");
    });
});

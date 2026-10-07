export class ApiError extends Error {
    constructor(
        message: string,
        public code = "",
        public status = 0,
        public requestId = "",
    ) {
        super(message);
    }
}
let companyId = "";
export function setApiCompany(id: string) {
    companyId = id;
}
export function query(
    values: Record<string, string | number | undefined | null>,
) {
    const params = new URLSearchParams();
    Object.entries(values).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== "")
            params.set(key, String(value));
    });
    return params.toString() ? "?" + params : "";
}
export async function api<T>(
    path: string,
    options: {
        method?: string;
        body?: unknown;
        version?: number;
        key?: string;
    } = {},
): Promise<T> {
    const method = options.method || "GET";
    const headers: Record<string, string> = {};
    const selectedCompany = companyId;
    if (selectedCompany) headers["X-Company-Id"] = selectedCompany;
    if (options.body !== undefined)
        headers["Content-Type"] = "application/json";
    if (options.version !== undefined)
        headers["If-Match"] = String(options.version);
    if (options.key) headers["Idempotency-Key"] = options.key;
    if (method !== "GET") {
        const csrf = await api<{ headerName: string; token: string }>(
            "/auth/csrf",
        );
        headers[csrf.headerName] = csrf.token;
    }
    let response: Response;
    try {
        response = await fetch("/api/v1" + path, {
            method,
            headers,
            credentials: "same-origin",
            cache: "no-store",
            body:
                options.body === undefined
                    ? undefined
                    : JSON.stringify(options.body),
            signal: AbortSignal.timeout(25000),
        });
    } catch {
        throw new ApiError(
            method === "GET"
                ? "连接服务失败，请检查网络并重试。"
                : "未收到服务响应，请先刷新确认操作结果，避免重复提交。",
            "NETWORK_ERROR",
        );
    }
    const data = await response.json().catch(() => null);
    if (!response.ok) {
        const error = new ApiError(
            data?.message || `请求失败（${response.status}）`,
            data?.code,
            response.status,
            data?.requestId,
        );
        if (error.status === 401 && path !== "/auth/login")
            window.dispatchEvent(new Event("session-expired"));
        if (error.code === "PASSWORD_CHANGE_REQUIRED")
            window.dispatchEvent(new Event("password-required"));
        throw error;
    }
    return data?.data as T;
}

# 基础接口与开发约束

## 已可调用接口

| 方法 | 路径 | 要求 | 返回 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | 无认证 | 标准 Actuator 健康结果 |
| GET | `/api/v1/auth/csrf` | 无认证；保存 Cookie | `data.headerName`、`data.token` |
| POST | `/api/v1/auth/login` | JSON、会话 Cookie、CSRF | `data.userId` |
| POST | `/api/v1/auth/logout` | 会话 Cookie、CSRF | 退出并失效会话 |
| GET | `/api/v1/me/companies` | 已登录 | 当前有效成员所属公司 |
| GET | `/api/v1/projects` | 已登录、`X-Company-Id` | 授权项目列表 |
| GET | `/api/v1/system/info` | 已登录 | 基础版本及已实现能力 |
| GET | `/v3/api-docs` | 仅 dev profile 启用 | 实际已注册接口文档 |

成功响应：`{"data":...,"requestId":"..."}`。错误：`{"code":"...","message":"...","requestId":"...","details":{}}`。基础列表直接在 data 中返回数组；业务分页 DTO 将由集成人统一后扩展，当前不能假定已有 cursor 分页。

登录请求：`{"username":"admin","password":"本地配置密码"}`。密码用 BCrypt 保存。登录成功后换 sessionId 并清除旧 CSRF，下一次写操作重新 GET csrf。退出使用 Spring Security LogoutFilter，业务方法不手工删除数据库会话。

## 当前错误码

- 400 `INVALID_REQUEST`：参数校验、缺少公司头、错误 UUID、未知 JSON 字段。
- 401 `UNAUTHENTICATED` / `INVALID_CREDENTIALS`：未登录或凭据错误。
- 403 `ACCESS_DENIED`：安全过滤器权限或 CSRF 失败。
- 404 `NOT_FOUND`：不存在或不属于该调用者授权范围。
- 503 `AI_NOT_CONFIGURED`：默认模型网关未配置；暂未对外暴露分析接口。
- 500 `INTERNAL_ERROR`：服务异常；日志通过 requestId 关联，不向客户端返回堆栈。

## 后续模块必须实现的机制

`Idempotency-Key`、`If-Match`、完整 RBAC、写审计、容量 guard、异步作业和 Outbox 尚未实现，不能只接收请求头便宣称有保障。身份模块是最小可用认证，不等于完整账号生命周期和生产安全策略。

Spring MVC 的请求参数用 `@Valid`；公司 ID 从头选择后通过 CompanyAccess 验证。异步作业显式携带公司和受限 actor 上下文，不依赖线程继承。按公司查询使用参数绑定，禁止客户端提供任意 SQL/排序表达式。

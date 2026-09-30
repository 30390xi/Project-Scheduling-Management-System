# scheduler-identity 接口说明（第一阶段）

模块：组织、项目与权限（工具 A，分支 `feature/identity-project`）。
通用约定见 `docs/foundation-api.md`：所有接口要求登录会话；写操作要求 CSRF token；公司上下文通过
`X-Company-Id` 头选择，服务端始终验证当前用户在该公司的有效成员身份，跨公司一律 404。

## 权限矩阵

| 接口 | 公司管理员 | 项目负责人（OWNER） | 普通成员 |
| --- | --- | --- | --- |
| GET `/api/v1/members` | 是 | 是（成员名册） | 是（成员名册） |
| POST `/api/v1/members` | 是 | 否 | 否 |
| PUT `/api/v1/members/{id}/role` | 是 | 否 | 否 |
| POST `/api/v1/members/{id}/disable` / `enable` | 是 | 否 | 否 |
| POST `/api/v1/members/{id}/password-reset` | 是 | 否 | 否 |
| POST `/api/v1/me/password` | 本人 | 本人 | 本人 |
| POST `/api/v1/projects` | 是（创建者自动成为 OWNER） | 否 | 否 |
| GET `/api/v1/projects/{id}` | 是 | 本项目 | 本项目成员 |
| PUT `/api/v1/projects/{id}` | 是 | 本项目 | 否 |
| PUT `/api/v1/projects/{id}/policy` | 是 | 本项目 | 否 |
| GET `/api/v1/projects/{id}/members` | 是 | 本项目 | 本项目成员 |
| POST/PUT/DELETE `/api/v1/projects/{id}/members[/{mid}]` | 是 | 本项目 | 否 |
| GET `/api/v1/projects`（列表） | 全公司项目（管理入口） | 参与项目 | 参与项目 |

管理员的项目管理能力只覆盖项目元数据与成员关系，不自动获得项目内容（任务/附件）读取权限。

## 新增接口

### 成员管理

- `GET /api/v1/members`：公司成员名册。返回 `[{memberId,userId,username,role,status,version}]`。
- `POST /api/v1/members`：创建/邀请成员，体 `{"username":"...","role":"ADMIN|MANAGER|MEMBER"}`。
  - 用户名不存在：创建账号并生成 20 位一次性临时密码，只在响应 `temporaryPassword` 返回一次，
    账号标记 `must_change_password`，首次登录后必须先改密；不落任何日志。
  - 用户名已存在：直接加入本公司，不返回临时密码（沿用其原密码）。
  - 已是本公司成员：409 `MEMBERSHIP_EXISTS`。
- `PUT /api/v1/members/{memberId}/role`：变更角色，体 `{"role":"...","reason":"可选"}`，必须带
  `If-Match: "<version>"`。不能降低自己的 ADMIN 角色（409 `CANNOT_CHANGE_OWN_ROLE`）。
- `POST /api/v1/members/{memberId}/disable`：禁用成员，体 `{"reason":"必填"}`。禁用立即生效：
  该成员身份的所有公司级接口返回 404，`/me/companies` 不再列出该公司。不能禁用自己
  （409 `CANNOT_DISABLE_SELF`）。重复禁用幂等返回当前状态。
- `POST /api/v1/members/{memberId}/enable`：恢复成员身份。
- `POST /api/v1/members/{memberId}/password-reset`：管理员重置密码，体 `{"reason":"必填"}`。
  生成一次性临时密码并撤销该用户**全部**会话，临时密码只在响应出现一次。

### 密码管理

- `POST /api/v1/me/password`：体 `{"currentPassword":"...","newPassword":"..."}`。
  当前密码错误返回 401 `INVALID_CREDENTIALS`；新密码须 12～72 个 UTF-8 字节且首尾非空白
  （400 `INVALID_PASSWORD`）。成功后撤销本人**其他**会话，保留当前会话，解除强制改密标记。

### 项目管理

- `POST /api/v1/projects`：体 `{"name":"..."}`。仅公司管理员；创建者自动成为项目 OWNER。
- `GET /api/v1/projects/{id}`：项目元数据 `{id,name,autoAssignEnabled,version}`。
  非成员且非管理员返回 404。
- `PUT /api/v1/projects/{id}`：改名，体 `{"name":"..."}`，必须带 `If-Match`。
- `PUT /api/v1/projects/{id}/policy`：派单策略，体 `{"autoAssignEnabled":true,"reason":"可选"}`，
  必须带 `If-Match`。审计留痕并递增 `planning_guard.revision`。
- `GET /api/v1/projects/{id}/members`：项目成员 `[{memberId,userId,username,role,resourcePermission}]`。
- `POST /api/v1/projects/{id}/members`：体 `{"memberId":"...","role":"OWNER|MEMBER","resourcePermission":false}`。
  目标成员必须是本公司 ACTIVE 成员，否则 404；重复加入 409 `PROJECT_MEMBERSHIP_EXISTS`。
- `PUT /api/v1/projects/{id}/members/{memberId}`：更新角色与资源授权，体同上加 `reason`。
  `resource_permission` 是项目资源授权标记，供排期模块筛选可派成员（由工具 C 消费）。
- `DELETE /api/v1/projects/{id}/members/{memberId}?reason=可选`：移出项目。
  项目至少保留一名 OWNER，移除/降级最后一名 OWNER 返回 409 `LAST_OWNER_REQUIRED`。

## 认证加固

- 登录限流：同一账号连续失败达到阈值（默认 5 次，`scheduler.auth.login.max-failed-attempts`）后锁定
  （默认 10 分钟，`lock-minutes`），锁定期内一律 429 `LOGIN_RATE_LIMITED`，不验证密码；
  单 IP 失败窗口限流默认 30 次/10 分钟（内存窗口）。失败计数不区分账号是否存在，不泄露账号存在性。
- 会话撤销：修改密码撤销本人其他会话；管理员重置密码撤销该用户全部会话；退出登录行为不变。
- 强制改密：持临时密码的账号在改密前只能访问 `/auth/csrf`、`/auth/logout`、`/me/password`，
  其余接口返回 403 `PASSWORD_CHANGE_REQUIRED`。

## 高影响操作审计

成员创建/角色变更/禁用/启用/密码重置、项目创建/改名/策略变更/项目成员增删改均写入
`identity_audit_event`（公司、操作人、动作、对象、原因、requestId、时间）。统一审计查询接口由集成人协调。

## 与排期版本契约的协作

成员禁用/启用/角色变更、项目成员增删改、项目策略变更会在同一事务内递增
`planning_guard.revision`（TRD 9.1：影响可行性的写入），使依赖旧快照的模拟方案失效。

## 新错误码

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_PASSWORD` | 密码不符合策略 |
| 400 | `IF_MATCH_REQUIRED` | 更新操作缺少 If-Match |
| 403 | `PASSWORD_CHANGE_REQUIRED` | 持临时密码未改密 |
| 409 | `VERSION_CONFLICT` | If-Match 版本过期 |
| 409 | `MEMBERSHIP_EXISTS` / `PROJECT_MEMBERSHIP_EXISTS` | 重复加入 |
| 409 | `CANNOT_DISABLE_SELF` / `CANNOT_CHANGE_OWN_ROLE` | 自我锁定保护 |
| 409 | `LAST_OWNER_REQUIRED` | 项目至少一名负责人 |
| 429 | `LOGIN_RATE_LIMITED` | 登录限流/账号锁定 |

## 数据库迁移

`db/migration/identity/V202609300615001__identity_stage1.sql`：
`app_user` 增加 `must_change_password`、`password_changed_at`、`failed_login_attempts`、`locked_until`；
新建 `identity_audit_event`。不改基础 V1/V2。

## 待集成人协调的契约提案

以下能力第一阶段在 identity 内部实现，建议集成人评估后沉淀到 `shared.contract` 或统一基础设施：

1. `CompanyAccess` 扩展：`ProjectMembership requireProjectMembership(userId, companyId, projectId)`，
   返回项目角色与 `resourcePermission`。消费者：工具 B（任务提交/改派校验）、工具 C（排期候选过滤）。
   当前 identity 内部已有实现（`ProjectService`），契约确定后抽出。
2. 成员排期属性快照端口：C 排期需要 `status`、`accept_new_assignments`、`accept_auto_assign`、
   `standard_minutes`、`daily_limit_minutes` 的批量只读快照，建议新增 `MemberDirectory` 契约接口，
   禁止跨模块直读 `company_member` 表。
3. 统一审计端口：identity 当前写 `identity_audit_event` 表。建议集成人定义 `AuditSink` 契约与统一
   `audit_event` 存储（工具 D 的 audit 域），identity 改为调用该端口，本表数据可迁移或保留为身份域明细。
4. `planning_guard` 失效通知：identity 已在成员/项目/资源授权变更事务内直接递增 revision。
   若 C 希望统一管理 guard 写入口，可提供 `PlanningGuardService.bump(companyId)` 契约，identity 替换为调用方。

## 模块内集成测试说明

`src/test/resources/db/test-baseline/V0__test_baseline.sql` 复制了 app 模块 V1 基础表，仅用于
identity 模块在 Testcontainers 中独立装配测试，不影响 app 的迁移链；V1 演进时需同步该文件。

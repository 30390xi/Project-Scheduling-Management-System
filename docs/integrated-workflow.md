# 集成后的业务流程

所有业务接口使用真实登录会话，带 `X-Company-Id`；写接口还要带 CSRF token。公司头不构成权限。Java 21 + Spring Boot 3，未引入前端、微服务或新模型 SDK。

## 任务分析与确认

1. `POST /api/v1/tasks` 创建草稿。`POST /api/v1/tasks/{id}/submit` 提交，按现有接口携带 `If-Match`。
2. `GET /api/v1/tasks/{id}/ai-analysis-runs` 获取对应 runId、jobId 和状态；`GET /api/v1/ai-analysis-runs/{runId}` 查看结果。
3. 未配置模型时运行明确失败，管理员可以编辑任务的估算和技能，通过 MARK_READY 继续人工估算流程。
4. AI 分析成功时，读取建议及关键假设。若有缺失信息，先修改需求，再调用 `POST /api/v1/tasks/{id}/ai-analysis-runs` 重新分析；不得代替用户确认关键假设。
5. `POST /api/v1/ai-analysis-runs/{runId}/confirm`，请求体 `{"confirmAllAssumptions":true}`。只有项目管理人员可以实际确认；任务版本必须与分析快照一致。返回 taskIds 和 simulationJobId。
6. 首次确认才创建子任务、写依赖和发起模拟；再次确认返回第一次结果。父任务为汇总节点，不占容量。

## 排期模拟、冲突和提交

- `POST /api/v1/schedule-simulations`：`{"rootTaskIds":["任务 UUID"]}`，返回 jobId。支持根任务展开为叶子，并计算下游影响范围。
- `GET /api/v1/schedule-simulations/{jobId}`：获取独立的 planId、状态、version 和结果。jobId 不是 planId。
- `GET /api/v1/schedule-plans/{planId}`：查看 fragments 和 conflicts。READY 才可提交，INFEASIBLE 返回当前规则/搜索范围内的失败原因。
- `POST /api/v1/schedule-plans/{planId}/commit`：请求体 `{"version":1}`（使用查询返回的真实 version），请求头 `Idempotency-Key`。当前用户必须有项目管理权限。
- `PUT /api/v1/tasks/{taskId}/schedule-lock`：`{"locked":true,"reason":"已与客户确认"}`；解除锁定也必须提供原因。
- `GET /api/v1/schedules/members/{memberId}?from=2026-10-01&to=2026-10-31`：查询成员任务分配；容量统计沿用 `/members/{memberId}/capacity`。

普通模拟不会挤走无关任务；重排只改变显式任务及下游影响范围内的可移动片段。跨日任务保持同一负责人，进行中任务保留负责人和今天已开始片段；日级 FS 最早次日起排。硬指定成员无容量时返回无可行方案或顺延，不能偷偷解除指定。

## 执行与容量

状态转移沿用 `POST /api/v1/tasks/{id}/transitions` 与 If-Match。START 需要 READY、有效计划、前置验收完成，以及当前负责人今天的有效分配。完成/取消与容量状态更新在同一数据库事务内执行，未来可释放容量立即回收，历史及今天已经开始的占用转为 RETAINED。

剩余工时变更会使已有计划进入 STALE；重新模拟后确认，才更新未来分配。容量实际占用与 work-log 实际工时分别统计。

## 配置与运行

模型配置沿用 `scheduler.agent.ai.*`：provider、base-url、api-key、model。密钥通过环境/秘密管理传入；provider 为 `openai-compatible` 且配置齐全才会调用真实供应商。默认关闭时不会生成假分析结果。

本地和 Docker 统一使用 `AI_PROVIDER`、`AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` 等环境变量。DeepSeek、GLM 标准 API 的选择、配置和一次性合成任务联调见 [模型接入指南](model-integration.md)。GLM Coding Plan 套餐不能作为本系统自建应用的运行时额度。

作业默认后台轮询；测试使用 `scheduler.agent.jobs.enabled=false` 后手动 poll 保证时序确定。分析外部调用预算和截止保存在 ai_analysis_run，同一运行的重试不会重置额度。缓存包含技能目录和影响结果的策略参数。

新增 SQL 放在 agent、planning 和 app 的 integration 迁移目录。历史上已经应用到共享环境的脚本不应改名或改内容；部署前应在上一发布版本数据库副本验证升级。完整测试使用 PostgreSQL 17 Testcontainers。

# 模型接入与首轮联调

核对日期：2026-10-05。后端继续使用 Java 21 + Spring Boot 3，通过现有 Spring RestClient 调用 OpenAI 兼容的 `chat/completions`；没有新增模型 SDK。

## 选择供应商

| 方案 | 本系统使用方式 | Base URL | 模型示例 |
| --- | --- | --- | --- |
| DeepSeek 标准 API | 建议作为首次联调方案，使用独立 API Key 和账户额度 | `https://api.deepseek.com` | `deepseek-flash` |
| 智谱 GLM 标准 API | 同一适配器，更换地址、模型和 Key | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` |
| GLM Coding Plan | 用于官方支持的开发工具；不作为本系统运行时套餐 | 不配置 Coding 专用端点 | — |

智谱明确要求自建应用、网站、机器人、SaaS 等使用标准 API 服务，不能把 Coding Plan 订阅额度当作业务系统 API 额度。即使账号或密钥体系相关，标准 API 仍按对应服务计费。模型名与开放权限以账户控制台为准；示例来自当日官方文档，不在业务代码中写死。

官方依据：[DeepSeek 首次调用](https://api-docs.deepseek.com/)、[JSON 输出](https://api-docs.deepseek.com/guides/json_mode/)、[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)、[智谱标准接口](https://docs.bigmodel.cn/cn/api/introduction)、[Coding Plan 使用范围](https://docs.bigmodel.cn/cn/coding-plan/faq)。

## 本地配置

如果已有 `.env`，只补充下面这些键，不覆盖已有数据库和登录配置。第一次才执行 `cp .env.example .env`。在本地编辑器填写真实 Key，不发到聊天、不放入 Git；`.env` 已被忽略，可用 `chmod 600 .env` 限制本地读取权限。

```dotenv
AI_PROVIDER=openai-compatible
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-flash
AI_API_KEY=在本地填写真实密钥
AI_JSON_MODE=true
AI_THINKING_MODE=disabled
AI_MAX_OUTPUT_TOKENS=8192
AI_REQUEST_TIMEOUT=45s
AI_RUN_DEADLINE=180s
AI_MAX_CALLS_PER_RUN=4
```

GLM 标准 API 改成对应 Base URL、账户可用的模型和标准 API Key。只有模型支持时才发送 `thinking` 参数；`AI_THINKING_MODE=provider-default` 会完全省略该参数，适合其他兼容供应商。首次 DeepSeek 联调显式关闭思考，避免供应商默认启用思考造成额外等待；效果评估后再比较 enabled 模式。

Base URL 不带 `/chat/completions`，适配器会追加它。`AI_MAX_OUTPUT_TOKENS` 支持 256–32768，同时还应符合所选模型限制。截断输出不会被当作成功分析。开启 JSON 模式只保证格式倾向，仍须通过技能、估算、依赖和假设等业务校验。

`AI_PROVIDER=disabled` 关闭调用；缺少地址、模型或 Key 时保持明确的未配置失败。`AI_*` 通过 app 的 application.yml 映射到 `scheduler.agent.ai.*`。`scripts/dev.sh` 和 Compose 读取 `.env`，直接 `java -jar` 必须自行注入环境变量。配置修改后重启应用。不要输出完整环境、HTTP 请求头或 `docker compose config` 的展开结果，其中可能包含密钥。

## 先做一次模型联调

```bash
./scripts/check-ai.sh --live
```

此命令是显式的真实调用：使用同一份 app 配置和生产网关，发送一条合成的 Java 函数开发任务，只调用一次，无重试，不读取项目任务、不访问数据库、不创建排期。会产生供应商标准 API 费用。需要 JDK 21/Maven，无需启动 PostgreSQL。

成功会输出 `AI_LIVE_CHECK_OK`、耗时、供应商返回的 token 数和子任务数；同时验证模型输出符合本项目业务结构。失败会返回非零退出码，不打印密钥、模型正文或思考内容。普通 `test` / `verify` / `-Pintegration verify` 不选择 `*LiveCheck`，不会触发付费模型调用。

没有 `--live` 或配置不完整时，脚本直接退出。脚本用于本地可信 `.env`；和 dev.sh 一样以 shell 读取文件，不要 source 外部未知来源的环境文件。

## 再验证业务闭环

1. 用 `./scripts/dev.sh` 启动；通过会话登录并配置公司技能目录、成员认证技能和每日容量。
2. 创建并提交一个简单任务；查询分析运行，确认状态和建议。详细接口见 [集成使用说明](integrated-workflow.md)。
3. 人工核对分钟估算、验收标准和关键假设；有缺失信息先补充任务，再重新分析。
4. 明确确认建议后生成任务/子任务，等待排期模拟完成；查看冲突，再显式提交 READY 方案。
5. 验证一个首选成员已满的任务、一个带前置依赖的大任务，以及一个无合格成员的任务。前两者应按确定性规则改派/分期，后者应返回冲突。

模型只做需求分析、工时建议、技能和拆分建议；最终选择成员、校验容量与依赖、写任务和容量仍由代码执行。模型不能直接批准假设、提高权限或提交排期。

切换供应商或思考/输出配置会隔离分析缓存。正式切换前等待进行中的分析完成，避免一个运行跨配置；当前没有多供应商并行路由或自动故障切换。

## 错误排查与质量验收

| 现象 | 处理 |
| --- | --- |
| AI_NOT_CONFIGURED | 检查 provider 是否启用、三个必填配置及重启是否生效 |
| 401/403 | 检查密钥所属平台、标准 API 权限与模型权限 |
| 400/404 等请求拒绝 | 检查模型名、Base URL、JSON/thinking 参数支持情况；不要改成 Coding 专用端点绕过计费 |
| 余额不足/402 | 到供应商控制台检查标准 API 余额；订阅套餐不等于标准 API 额度 |
| 429/5xx/连接超时 | 生产作业在既有持久化预算内重试；联调脚本单次失败后退出 |
| 输出截断、空内容或结构错误 | 缩小需求范围，必要时调整输出上限；生产结构修复仍消耗同一运行预算 |
| 无可行排期 | 查看技能、成员日历、容量、锁定和截止冲突，不能让模型绕过约束 |

单次连通不能证明估时准确。接通后先人工评估 10–20 条覆盖简单任务、拆分任务和信息不足任务的样本，记录结构通过率、缺失信息质量、人工修改幅度、延迟和 token 使用；积累实际工时后再比较估算误差。真实项目任务会发送到选定供应商，先用合成数据完成首轮验证。

当前限制：每次请求有输出 token 上限，每个运行最多 4 次调用（包含修复/重试），但这不是公司每日费用封顶；输入也产生费用。日/月费用预算与多模型质量比较尚未实现。未填写 Key 前不会声称真实供应商已验证。

## 本轮验证

2026-10-05 执行 `./scripts/mvn.sh -B -ntp -Pintegration verify`：200 项测试通过，0 失败、0 错误、0 跳过，包含 PostgreSQL 17 集成测试。新增覆盖本地 HTTP 服务上的真实客户端请求（根路径、`/v1`、GLM 前缀及尾斜杠）、JSON/思考/输出参数、截断拒绝、异常脱敏，以及 application.yml 的环境变量绑定和默认关闭行为。Compose 配置和联调脚本语法校验通过；没有 `--live` 时脚本按预期退出。

2026-10-05 已使用本地 `.env` 的 DeepSeek 标准 API 配置执行一次 `./scripts/check-ai.sh --live`。配置模型 `deepseek-flash`，关闭思考模式；网关调用耗时 2663 ms，供应商返回 totalTokens=1106。合成的分钟格式化函数任务返回单任务建议（subtasks=0），通过生产网关及结构/业务校验，测试 1 项通过。密钥不记入文档或 Git。

此次真实联调没有发送现有项目数据、写数据库或提交排期。真实业务端到端验收及模型估时质量评估仍待开展；本次不包含数据库迁移或远程推送。

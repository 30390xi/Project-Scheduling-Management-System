# 四路 AI 开发评估（2026-09-30）

> 后续状态：本报告记录修复前的评估。2026-10-05 已完成对应修复与主流程集成，最新结果见 [修复验证记录](fix-verification-2026-10-05.md)。

结论：四路都有可复用的实现和真实测试，但目前只能按“模块阶段性交付”接收，不能按“AI 项目排期系统已完成”验收。存在已复现的跨公司账号接管、任务分析链路中断以及规则校验缺陷；自动排期核心仍缺失。建议继续在隔离集成环境修复和补齐，暂不作为可上线版本。

## 1. 评估对象与方法

| 工具 | 本地目录 | 分支 | 审查提交 |
| --- | --- | --- | --- |
| A | `/Users/xgs/中智游/scheduler-ai-a` | `feature/identity-project` | `f3bddb0` |
| B | `/Users/xgs/中智游/scheduler-ai-b` | `feature/task-workflow` | `af3b51b` |
| C | `/Users/xgs/中智游/scheduler-ai-c` | `feature/capacity-planning` | `3468911` |
| D | `/Users/xgs/中智游/scheduler-ai-d` | `feature/ai-agent` | `52aae66` |

依据：根目录 PRD/TRD、AGENTS.md、`docs/parallel-development.md`、四份任务书及实际生产代码。采用 Java 21、Spring Boot 3，数据库测试使用真实 PostgreSQL 17 Testcontainers。

四个原目录分别执行 `./scripts/mvn.sh -B -ntp -Pintegration verify`，均成功。随后从 main 的 `d54505b` 建立隔离 worktree，依次合并四支，无文本冲突，完整构建与已有测试均通过。

隔离目录：`/Users/xgs/.codex/worktrees/review-four-modules/项目排期管理系统`，集成提交 `fd8fc23`。原 main 未合入业务代码，四个开发目录未修改。本次仅在隔离目录增加回归测试，并在主目录保存评估资料；未推送远程、未做缺陷修复。

## 2. 每个 AI 做得怎么样

| 工具 | 实际产出 | 评价 | 尚不能验收的原因 |
| --- | --- | --- | --- |
| A：身份、项目、权限 | 成员管理、项目管理、角色、临时密码、强制改密、限流、会话撤销、权限测试 | 功能覆盖较完整，有真实 HTTP/会话测试 | 公司管理员权限错误地延伸到全局账号密码，存在可复现账号接管 |
| B：任务、工作流 | CRUD、版本、任务树、DAG、状态机、实际/剩余工时、TaskCatalog | 业务骨架较完整，依赖和父子任务测试有价值 | 开工校验缺少当天实际分配；完成/取消与容量账本未连接；提交无法进入 D 的分析处理；附件/CSV 未完成 |
| C：容量、排期 | 技能、工作日历、容量桶、占用账本、净变化事务、幂等和数据库约束 | 已实现部分的并发与一致性验证较扎实 | 只完成容量基础设施；模拟、自动选人、满额改派、PlanningService、锁定/重排、冲突和成员排期视图尚未实现 |
| D：AI、异步 | 模型网关、JSON/领域校验、估算、建议存储、作业租约、Outbox、通知、审计 | 覆盖面广，有结构化实现和固定样本测试 | 跨作业重试预算失效、确认条件反向、缓存漏上下文、幂等冲突未检查；人工确认到建任务/排期未接通 |

不按代码行数或测试数量给武断的完成百分比。A/B 更接近各自基础功能目标，C 的核心排期职责仍有明显缺口，D 的基础设施较多但关键边界需要返工。C 在自己的验证文档中明确列出了未完成项，这属于诚实的阶段交付，但不代表原任务书已经全部完成；并行指南的第一阶段本来就包含“模拟与原子提交”。

## 3. 按优先级排列的发现

### F1 · P1 · A：公司管理员可以接管其他公司账号【已复现】

位置：[MemberService.java:138](/Users/xgs/中智游/scheduler-ai-a/scheduler-identity/src/main/java/com/company/scheduler/identity/service/MemberService.java:138)，关联同文件第 55 行创建成员逻辑。

`create` 按全局 username 找到已有账号后，不经过本人接受邀请就将该账号加入当前公司；`resetPassword` 只验证当前公司的管理员身份，随后修改全局 `app_user` 密码并将临时密码返回给管理员。

在测试数据库中建立互不相关的 A、B 公司和两个账号，A 管理员添加 B 的账号、重置其密码、用临时密码登录并完成改密后，成功以该账号访问 B 公司项目，HTTP 返回 200。这不是单纯让别人被迫退出登录，而是取得对方身份及其其他公司的访问权。

修复要求：明确全局账号与公司成员身份的权限边界。已有账号加入公司应有本人接受机制；公司管理员不得仅因成员关系就重置可跨公司的全局账号密码。通过经过验证的本人找回或明确的平台账号管理权限处理。添加完整攻击链回归测试，不能只验证 `X-Company-Id` 越权。

### F2 · P1 · B/D：提交任务后分析作业立即失败【已复现】

位置：[TaskService.java:222](/Users/xgs/中智游/scheduler-ai-b/scheduler-task/src/main/java/com/company/scheduler/task/service/TaskService.java:222)、[AiAnalysisJobHandler.java:76](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisJobHandler.java:76)、[ReliableJobDispatcher.java:32](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/job/ReliableJobDispatcher.java:32)。

B 入队类型是 `TASK_ANALYSIS`，D 注册的处理器只处理 `AI_ANALYSIS`。在完整 Spring 应用和合并后的 PostgreSQL 迁移上，调用真实 TaskService 提交任务后执行 JobRunner，得到终态错误 `UNKNOWN_JOB_TYPE:TASK_ANALYSIS`，没有产生分析结果。

不能只改字符串：B 通用入队载荷只有 `aggregateVersion`，D 需要已经创建的分析运行及 `runId`，还缺任务文本、技能快照、提交人和版本转换。应由集成人确定共享分析请求契约或实现任务分析适配处理器，并保证任务提交、快照/运行和作业在正确事务边界内持久化。

### F3 · P1 · C：产品核心排期能力尚未实现【范围缺口】

证据：[C 的未完成清单](/Users/xgs/中智游/scheduler-ai-c/docs/verification-planning-c.md:56)。合并代码中 `PlanningService` 只有 common 接口，没有实现。

当前可以验证“已经给定任务、成员、日期和分钟后不超卖容量”，还不能根据任务和技能自己选人、跨日排期、当日满额自动换人，也没有 DAG 排期、硬截止/锁定校验、方案模拟与确认提交、变更重排、冲突解释、成员排期视图。这些正是用户最初要求的主要业务效果，不能因容量账本测试通过就视为已交付。

### F4 · P1 · B/C：开工仅检查状态，没有验证当日分配【已复现】

位置：[TaskTransitionService.java:68](/Users/xgs/中智游/scheduler-ai-b/scheduler-task/src/main/java/com/company/scheduler/task/service/TaskTransitionService.java:68)、[同文件 START 分支](/Users/xgs/中智游/scheduler-ai-b/scheduler-task/src/main/java/com/company/scheduler/task/service/TaskTransitionService.java:111)。

构造 READY、PLANNED 任务，但没有今天的占用片段，执行 START 仍成功。此用例直接设置 PLANNED 用于隔离规则；它证明状态机没有查当日占用，不代表当前已有正式排期提交入口可达。

PRD 第 325 行明确要求“当前日期有该任务片段”。此外 START 未同步 `execution_started`，C 账本仍可能把实际已经开始的片段当成可释放。应由共享端口在同一事务内校验当天片段、负责人和前置，标记占用开始；补“只排了明天、今天不能开工”的整合测试。

### F5 · P1 · B/C：完成/取消没有真正关闭容量占用【代码核查】

位置：[TaskTransitionService.java:127](/Users/xgs/中智游/scheduler-ai-b/scheduler-task/src/main/java/com/company/scheduler/task/service/TaskTransitionService.java:127)。

ACCEPT/CANCEL 只改变任务状态和 `ScheduleStatus.CLOSED`，以及写 task_status_event。注释称由 C 消费任务事件释放容量，但合并后的 C 没有对应消费者或共享释放调用，也没有该路径的可靠 Outbox。

一旦存在预留，任务结束后未来容量不会由这条路径回收，与 PRD 的 CLOSED 定义、AC-18 不符。本项依据代码链路确认，未计入七个动态复现用例。应保留历史和今日已开始片段、原子释放可释放片段，并对照账本验证，不能只改任务状态。

### F6 · P2 · D：作业重试重置单运行调用预算【已复现】

位置：[AiAnalysisJobHandler.java:109](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisJobHandler.java:109)、[同文件重试循环](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisJobHandler.java:193)。

每次 handler 执行都创建全新的 AiCallBudget；四次瞬时故障后抛出可重试异常，下一轮 job attempt 又有四次额度。测试在同一个 run 的两轮作业中观测到 8 次调用，超过 TRD 单运行最多 4 次要求。运行截止也随新预算重新开始；失败尝试的调用数未可靠累计。

修复要求：将调用总数和绝对截止与 run 绑定并持久化，跨租约/进程/作业重试共享，调用前原子扣减，记录失败尝试的耗用。

### F7 · P2 · D：未确认假设被当成已满足确认条件【已复现】

位置：[AiAnalysisJobHandler.java:241](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisJobHandler.java:241)。

`allKeyAssumptionsConfirmed` 使用 `noneMatch(confirmed == true)`，方向相反。测试输入含 `confirmed:false` 假设，建议的 blockingReasons 缺少 `UNCONFIRMED_KEY_ASSUMPTIONS`。

目前结果仍保存 `humanConfirmationRequired:true`，示例也被其他条件阻断，因此不能宣称已经发生自动越权派单；可以确认的是准入初判不正确。确认必须来自可信的人工确认记录，不能由模型声称确认；未取得的项目开关和硬约束结果也应显式待定或拒绝。

### F8 · P2 · D：建议缓存忽略当前技能目录【已复现】

位置：[AiAnalysisService.java:74](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisService.java:74)、[AiAnalysisJobHandler.java:95](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisJobHandler.java:95)。

输入哈希只包含正文、模型、提示版本。首次允许 JAVA 并生成成功建议，再以相同正文、不同幂等键和只允许 PYTHON 的目录提交，仍复用包含 JAVA 的旧建议。复用途径在当前目录校验之前返回。

修复要求：把影响结果的规范化上下文/版本纳入指纹，并在复用前按当前技能、Schema 和策略重校验。只复用模型原始建议与直接复用过去的准入结论，应分别处理。

### F9 · P2 · D：幂等键重用不同内容没有冲突【已复现】

位置：[AiAnalysisService.java:66](/Users/xgs/中智游/scheduler-ai-d/scheduler-agent/src/main/java/com/company/scheduler/agent/ai/AiAnalysisService.java:66)。

查询到公司内同类型、同幂等键作业后，直接返回旧 run，没有比较正文、任务 ID、技能或提交者。测试将同一任务的正文改掉、沿用键再次提交，没有抛出冲突。不同任务也可能被静默关联到旧运行。

修复要求：按 TRD 的公司、操作者、路由和键确定作用域，存储规范化请求 hash；同内容重放，不同内容 409。该问题与缓存不同：幂等用于识别同一次请求，缓存用于复用计算。

## 4. 测试证据与限制

完整合并后、增加评审用例之前的测试结果：

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| identity | 14 | 0 / 0 / 0 |
| task | 39 | 0 / 0 / 0 |
| planning | 44 | 0 / 0 / 0 |
| agent | 69 | 0 / 0 / 0 |
| app | 8 | 0 / 0 / 0 |
| 合计 | **174** | **0 / 0 / 0** |

这些是单元、Web 切片和 PostgreSQL 集成测试的合计，不是 174 个完整产品流程。app 原来的八个测试主要验证基础认证、迁移、启动及约束，没有覆盖“上传任务→分析→确认→派单→完成→释放”的流程。

新增七个针对安全性质/规则的回归测试，分别独立执行，均在断言处失败，错误数为 0：

| 测试 | 实测结果 | 对应发现 |
| --- | --- | --- |
| IdentityStage1IT.reviewCompanyAdminCannotTakeOverAnotherCompanyAccount | 攻击链完成后，对另一公司项目请求得到 200 | F1 |
| FoundationIT.reviewTaskSubmissionMustDispatchARecognizedAnalysisJob | UNKNOWN_JOB_TYPE:TASK_ANALYSIS | F2 |
| TaskFlowIT.reviewStartRequiresActualAllocationToday | 无今日分配仍可 START | F4 |
| AiAnalysisIT.reviewProviderBudgetSurvivesJobRetry | 同一 run 两轮共 8 次调用，上限应为 4 | F6 |
| AiAnalysisIT.reviewUnconfirmedAssumptionMustBlockAdmission | 缺少未确认假设阻断原因 | F7 |
| AiAnalysisIT.reviewChangedSkillCatalogMustNotReuseOldSuggestion | 当前只允许 PYTHON，结果仍含 JAVA | F8 |
| AiAnalysisIT.reviewIdempotencyKeyWithDifferentPayloadMustConflict | 修改请求后同键调用未拒绝 | F9 |

回归用例仅存在隔离 worktree；另保存 [regression-tests.patch](/Users/xgs/中智游/项目排期管理系统/docs/reviews/regression-tests.patch) 便于修复时带回对应分支。它们用于暴露真实错误，当前预期为红色，不应通过删除断言或忽略测试来验收。

在隔离 worktree 中可依次复现（使用 clean 避免 Failsafe 读取上一次模块测试结果；不要同时运行这些命令以免竞争同一个 target）：

```bash
./scripts/mvn.sh -B -ntp -pl scheduler-identity -am -Pintegration '-Dit.test=IdentityStage1IT#review*' -Dfailsafe.failIfNoSpecifiedTests=false clean verify
./scripts/mvn.sh -B -ntp -pl scheduler-task -am -Pintegration '-Dit.test=TaskFlowIT#review*' -Dfailsafe.failIfNoSpecifiedTests=false clean verify
./scripts/mvn.sh -B -ntp -pl scheduler-agent -am -Pintegration '-Dit.test=AiAnalysisIT#review*' -Dfailsafe.failIfNoSpecifiedTests=false clean verify
./scripts/mvn.sh -B -ntp -pl scheduler-app -am -Pintegration '-Dit.test=FoundationIT#review*' -Dfailsafe.failIfNoSpecifiedTests=false clean verify
```

日志位于 `/tmp/scheduler-review-combined.log`、`/tmp/scheduler-review-regression-a.log`、`/tmp/scheduler-review-regression-b.log`、`/tmp/scheduler-review-regression-d.log`、`/tmp/scheduler-review-regression-integration.log`；临时日志可能被系统清理，因此本报告保留关键结果。

验证限制：没有付费调用真实模型，无法评价真实工时估计准确率、拆分质量与真实供应商稳定性；没有性能压测或生产数据库升级演练；未实现功能不能由已有测试推断为可用。没有前端是原任务允许的范围，不作为扣分项。

## 5. 用户实际能得到什么

| 原始需求 | 当前效果 |
| --- | --- |
| 管理成员、项目、录入和查询任务 | 已有基础接口，需先修复账号安全问题 |
| 上传附件/批量任务 | 普通任务录入已有；附件安全解析、CSV 导入未完成 |
| AI 估时、拆解 | D 服务内部可用固定样本跑通建议生成；实际任务提交尚接不进去，拆解结果未确认落为可执行子任务 |
| 根据技能与工时自动选人派发 | 未实现完整流程 |
| 容量满额禁止继续占用 | 给定分配的账本层有并发约束和测试保障 |
| 满额自动改派其他人 | 未实现选人及重排引擎 |
| 大任务按依赖和负载分别排期 | 任务树/DAG 与 AI 建议两端存在，中间确认和排期未连接 |
| 任务列表 | 已实现 |
| 成员排期视图和冲突提示 | 有容量查询；任务级成员排期与完整冲突解释未完成 |

## 6. 建议下一轮任务与验收条件

先由集成人统一分析请求、人工确认、任务快照、容量生命周期、guard、Outbox 的共享契约；这些契约本来就明确由集成人负责，跨模块断点不能只归咎于某一个 AI。各分支同步契约后继续开发。

| 负责人 | 下一轮重点 | 必须提交的验收证据 |
| --- | --- | --- |
| A | 修复 F1；明确全局账号与公司权限；复核邀请/重置/会话边界 | 两公司真实 HTTP 攻击链被拒绝，合法本人改密仍正常 |
| B | 与 D 修复提交分析；与 C 补开工、结束、剩余工时变更的容量协作；补附件/导入计划 | 提交产生有效分析运行；没有当日分配不能开工；变更使旧计划失效；完成/取消释放正确 |
| C | 补 PlanningService、确定性排期、满额改派、方案提交、锁定/重排、视图与冲突；补任务复合外键的集成迁移 | 两人技能筛选与满额改派、大任务 DAG/跨日、硬截止无解、并发提交、多日回滚、AC-18 全部在真实 PostgreSQL 验证 |
| D | 修复 F6–F9；对齐 B 的入口和结果确认；把建议经确认交给任务及规则排期 | 四个新增回归测试变绿；整合流程可追踪；真实模型连通测试与 CI 固定样本测试分离 |
| 集成人 | 契约与事务边界、统一锁顺序、迁移整合、端到端用例 | 从任务提交到分析/确认/排期/完成/容量回收的完整流程；所有旧测试与新增测试通过 |

评估不要求推倒重写。保留各自已验证的模块实现，先修安全和接口衔接，再完成 C 的排期核心及 B/D 确认流程；验收应以具体业务场景和可复现结果为准。

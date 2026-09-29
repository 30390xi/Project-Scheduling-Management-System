# 四路分支开发指南

## 分工与文件归属

| 工具 | 建议分支 | 主要目录 | 第一阶段交付 |
| --- | --- | --- | --- |
| A | `feature/identity-project` | `scheduler-identity/` | 成员/项目管理、完整权限、认证加固 |
| B | `feature/task-workflow` | `scheduler-task/` | 任务 CRUD、版本、DAG、状态机、工时 |
| C | `feature/capacity-planning` | `scheduler-planning/` | 技能/日历、容量账本、模拟与原子提交 |
| D | `feature/ai-agent` | `scheduler-agent/` | AI 网关、结构校验、作业、Outbox/通知 |
| 集成人 | `main` | `scheduler-common/`、`scheduler-app/`、根配置、文档 | 共享契约、装配、端到端验证、合并 |

模块内可以增加子包；A 管身份/项目，C 管成员技能和容量。成员身份的 `company_member` 基础表由 A 管字段；C 通过新增自己的技能/日历/容量表扩展，不在分支中各自复制一张成员表。

不要同时让两路实现同一个 Controller、修改全局 Security 配置或改共享 DTO。需要跨模块的新方法时，先由集成人在 common 添加接口并提交，各分支同步后再实现。

## 从基础提交创建独立工作目录

本地基础提交准备完成后，四个分支应指向同一个基础提交。若分支已存在，用现有分支；不要重置已有开发工作。每个工具选择以下方式之一：

- Codex 等支持托管 worktree 的工具：通过其 worktree 功能选择对应分支，获得独立目录。
- 其他工具：通过 Git 客户端创建四个独立 checkout，分别检出对应分支。
- 也可各自 clone 当前本地仓库；后续提交通过 PR/远程仓库或显式 fetch 合并。

每个目录都重新复制 `.env.example` 为 `.env`，设置不同 `SERVER_PORT`、`DB_PORT`。Compose 项目名使用各自 `COMPOSE_PROJECT_NAME`，例如 `scheduler-ai-a`；不要让四路连接同一个可写开发数据库。集成测试用 Testcontainers 自动隔离，不依赖 `.env`。

## 共享契约

`scheduler-common/.../shared/contract` 是最小起始契约：

| 接口 | 实现者 | 消费者 | 当前状态 |
| --- | --- | --- | --- |
| CompanyAccess | A | B/C/D | 已提供成员及项目访问实现 |
| TaskCatalog | B | C | 只有接口；按真实已确认任务构建快照 |
| PlanningService | C | B/D | 只有接口；请求模拟返回 SimulationJob.jobId，作业成功后获取独立 planId |
| AiModelGateway | D | D 的分析编排 | 默认关闭适配器，调用明确返回 503 |
| JobDispatcher | D | B/C | 只有接口；须与调用者事务协作持久化 |

接口是第一版集成边界，完整领域字段仍按 TRD 补齐，不能强行用不完整 DTO 承载全量规则。第一轮先实现本模块领域能力，用测试替身替代尚未合并的其他模块实现；不要在生产容器中注册虚假成功实现。

**首次契约协调清单**：人工确认后的 TaskSnapshot 扩展字段、技能/资源快照、planning_guard 事务协作端口、可靠 Outbox 写入口、审计事件 DTO。由集成人一次定义，四路同步；所有异步接口须区分 jobId 与 planId，禁止客户端猜测类型。

## 数据库迁移

基础 V1/V2 归 app，已有项目不可修改其内容。四路新增 SQL 放在各自模块：

```text
scheduler-identity/src/main/resources/db/migration/identity/
scheduler-task/src/main/resources/db/migration/task/
scheduler-planning/src/main/resources/db/migration/planning/
scheduler-agent/src/main/resources/db/migration/agent/
```

使用生成时 UTC 时间戳 + 工具编号，示例：`V202609290900001__identity_members.sql`，末位 1/2/3/4 对应 A/B/C/D。跨模块表引用只引用已合并基础或契约，尚未合并的外键在集成迁移中补齐。

Flyway 默认禁止乱序迁移。分支合并后，集成人检查版本顺序：**未发布到共享环境的脚本**可在合并前统一调整版本并重新创建各自可丢弃的开发数据库；**已经应用到共享环境的脚本**不得改名/改内容，应追加新迁移。禁止为了合并方便直接打开生产 `out-of-order` 或 `clean`。每轮合并在全新测试库验证全链路，并在上一发布版本的测试库验证升级。

## 合并与验证节奏

1. 四路从统一基线开始，各自只改负责模块及相应测试/迁移。
2. 首次先合并公共契约澄清，再做领域开发；可每天同步 main。
3. 每个变更附：实现范围、接口/数据库变更、执行的测试、未完成项。
4. 建议先 A 身份与 B 任务基础，再 C 容量与排期，再 D 分析/异步端到端；D 可先并行完成独立网关和作业机制。
5. 每合并一支，在 main 执行 `./scripts/mvn.sh -B -ntp -Pintegration verify`，检查数据库迁移顺序与完整启动。
6. 共享契约冲突由集成人解决；不要以删除别的模块、跳过测试或改成可空假结果通过构建。

## 完成定义

- 新接口有权限、验证、错误码和可追踪 requestId。
- 写操作按需求完成版本/幂等/审计；不要误以为基础工程已统一实现这些业务机制。
- 有业务测试；涉及容量的变更有真实 PostgreSQL 并发测试。
- 接口和生产类无临时成功 stub；模型不通时明确降级。
- 功能与 PRD/TRD 一致，不能通过改变上限、跳过依赖或删除历史来消除冲突。

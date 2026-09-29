# 工具 C：容量与排期引擎

请在 `feature/capacity-planning` 独立工作目录开发，主要修改 scheduler-planning。先读 AGENTS.md、PRD/TRD 与并行开发指南。

已有 CapacityPolicy 与边界测试。请实现：

1. 技能目录/认证、成员日历、半天假/调休、每日容量桶和占用明细。
2. PostgreSQL CHECK、公司 guard、固定锁顺序、净变化事务、明细对账约束；任务叶子和人选数据来自 B 的契约。
3. DAG 上的日级排期、跨日工时、同日满额改派、技能硬过滤、硬截止继承、锁定与无解解释。
4. 实现 PlanningService，先模拟不占容量，确认时重读版本与硬约束，原子提交，加入幂等。
5. 变更影响闭包、剩余工时重排、冲突和成员视图接口；不自动提高容量或改变进行中负责人。
6. 严格遵守日级 FS：后置最早次日，首发不伪装小时级排期。
7. 用真实 PostgreSQL 做并发抢占、多日回滚、日历变更竞争测试；覆盖 AC-03/04/05/06/09/11/12/13/14/15/17/18/19/21/22/23。

迁移放 `db/migration/planning`。CompanyAccess 由 A 提供，TaskCatalog 由 B 提供，作业/事件由 D 提供；跨模块缺口先提案，不导入其 Repository。

测试命令：`./scripts/mvn.sh -pl scheduler-planning -am verify`，SQL 和事务测试必须纳入 `-Pintegration`。

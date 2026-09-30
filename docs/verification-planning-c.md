# 工具 C（容量与排期）第一阶段验证记录

分支：`feature/capacity-planning`，模块：`scheduler-planning`。
本文件随第一阶段提交更新，供集成人核对迁移顺序、共享契约与验证范围。

## 已实现范围（第一阶段）

1. 技能目录与成员技能：公司级技能目录（唯一 code、可停用）、成员认证技能（管理员写入）与自报技能（本人写入，不参与排期准入）。
2. 工作日历：公司工作日（节假日/调休/手工维护）与成员覆盖（请假分钟、额外不可用分钟、可选工作日标志）；无日历行时按周一至周五推断。仅允许维护今天及未来。
3. 容量账本：`member_day_capacity` 容量桶（唯一计数入口）与 `task_allocation` 占用明细（RESERVED/RETAINED/RELEASED）。
4. 原子占用机制：固定锁顺序（guard → 释放明细按 ID → 容量桶按 `(member_id, work_date)` 排序 → 分配明细）、带条件的净变化 UPDATE、整事务回滚、guard revision 递增。
5. 幂等记录：`planning_idempotency`，同键同内容重放、不同内容 409、失败可重试、并发只有一个业务执行。
6. REST：技能目录/认证、公司日历/成员日历、成员容量查询（C/U/R 及来源）、容量策略调整。

## 数据库变更（`db/migration/planning/`）

| 版本 | 内容 |
| --- | --- |
| `V202609300900003` | `skill`、`member_skill`（认证/自报互斥 CHECK） |
| `V202609300910003` | `calendar_day`、`member_calendar_override` |
| `V202609300920003` | `member_day_capacity`、`schedule_plan`、`task_allocation`、唯一/部分索引、对账与单一负责人约束触发器（DEFERRABLE INITIALLY DEFERRED） |
| `V202609300930003` | `planning_idempotency` |

要点：

- 触发器保证提交时 `occupied_minutes = SUM(RESERVED+RETAINED)`：只改桶计数、只写明细、改日期/改人员后旧桶失衡均会在 COMMIT 时被拒绝回滚。
- `uq_task_active_day` 保证每任务每日期至多一条 RESERVED；约束触发器保证同一任务所有 RESERVED 片段同一负责人（历史 RETAINED 可保留旧负责人）。
- 桶 CHECK：`occupied_minutes <= capacity_minutes`，分钟均为 30 的倍数。
- `task_allocation` 对 `task` 表的 `(company_id, task_id)` 外键**尚未创建**（task 表由 B 分支迁移建表），由集成迁移补齐；当前以应用层与索引过渡。

## 已验证不变量（真实 PostgreSQL 17，Testcontainers）

- 0 ≤ occupied ≤ capacity 在所有路径下成立；并发 20 事务抢占剩余 120 分钟仅 1 个成功（AC-11）。
- 多日占用最后一天失败 → 整事务回滚，先前日期无残留（TRD 17.2）。
- 改派事务中途失败 → 旧占用保持、新占用不存在；成功则新旧桶一次校验通过。
- 仅改桶计数不改明细 / 仅写明细不改桶 → 延迟约束触发器拒绝提交。
- 容量下调低于已保留占用 → `CAPACITY_BELOW_RETAINED` 整单拒绝（AC-14 的容量部分）；历史日期桶快照不创建、不重写（PRD 10.6）。
- 日历下调与新增占用并发 → guard 串行化，恰好一方胜出，最终状态一致。
- 幂等：同键同内容重放首次响应；不同内容 409 `IDEMPOTENCY_KEY_REUSED`；FAILED 可重试；并发同键仅一次业务执行。
- 历史日期占用、非 30 倍数分钟、已开始/已释放占用释放均被拒绝。

## 测试执行

- `./scripts/mvn.sh -B -ntp verify`：单元 + MockMvc 共 22 个通过（含既有 CapacityPolicyTest）。
- `./scripts/mvn.sh -B -ntp -Pintegration verify`：全仓库通过；planning 22 个集成测试 + app FoundationIT 8 个（完整迁移链路）。

## 待协调接口（契约提案，请集成人统一定义后四路同步）

1. **TaskSnapshot 扩展字段**（B 实现协议）：`nodeType`（LEAF/SUMMARY）、`dueType`（HARD/SOFT）、`priority`、`currentAssigneeId`、`planLocked`、`executionStarted`/`remainingMinutes`、项目 `resourcePermission`；`loadSchedulable` 需返回前置的预计完成日期或 DONE 实际完成日期，供"后置最早次日"计算。
2. **guard 协作端口**：B/D 在各自影响可行性的写事务内需要调用 `bumpRevision(companyId)`（与业务写入同事务）。建议在 `shared.contract` 增加 `PlanningRevisionGuard`，由 C 提供实现。
3. **Outbox 写入口**：提交事务需与审计/通知同事务落 Outbox 事件，建议由 D 提供 `OutboxWriter` 契约。
4. **审计事件 DTO**：技能认证、容量调整等需留痕，等集成人统一 DTO。
5. **幂等表命名**：本次使用 `planning_idempotency`（限定排期路由），避免与其他分支的通用 `api_idempotency` 冲突；如集成人统一为共享表，请在集成迁移中迁数据并废弃本表。
6. **guard 行创建**：C 的 `lockGuard` 已做缺行兜底创建；请 A 在公司创建事务内显式插入 `planning_guard` 行（TRD 9.2）。

## 未完成（第二阶段）

排期模拟（技能筛选/跨日/依赖/硬截止/beam search）、`PlanningService` 实现、方案提交原子化（幂等 + 版本 + 重校验）、锁定、重排影响闭包、冲突、成员排期视图、`/schedules/members`、异步 jobId 流程（依赖 D 的 JobDispatcher）。

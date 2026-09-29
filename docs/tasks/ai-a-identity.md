# 工具 A：组织、项目与权限

请在 `feature/identity-project` 独立工作目录开发，先阅读根 AGENTS.md、PRD/TRD 与并行开发指南。主要修改 scheduler-identity；需要修改 common/app 时先提交契约提案，由集成人落地。

基础已经提供 JDBC 会话登录、CompanyAccess 与公司/项目列表。请接续实现：

1. 公司成员查询、邀请/创建、禁用、角色管理；项目创建、更新、项目成员与资源授权。
2. 使用真实会话主体和公司/项目范围鉴权；管理员操作权限不自动授予所有项目内容读取权限。
3. 账号密码管理、登录限流、会话撤销；不得重新引入固定默认密码。
4. 更新实体使用版本控制，高影响操作记录原因，并与 planning_guard 版本变更契约协作。
5. 认证技能和容量策略由 C 管理；不要实现第二套技能/日历接口。
6. 加入跨公司、跨项目越权和禁用后访问测试，覆盖 PRD AC-20。

迁移放 `db/migration/identity`，不要改基础 V1/V2。测试命令：`./scripts/mvn.sh -pl scheduler-identity -am verify`，端到端使用 `-Pintegration verify`。

交付说明列出新增接口、权限矩阵、迁移、测试结果、待集成端口。不要扩大成前端或微服务项目。

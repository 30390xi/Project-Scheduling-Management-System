# AI Agent 项目排期管理系统

基于 **Java 21 + Spring Boot 3.5.16 + PostgreSQL 17** 的后端基础工程。采用 Maven 多模块组织代码，最终部署为一个应用，供四个 AI 工具在独立分支/工作目录中继续开发。

## 当前已经实现

- Java 21 / Spring Boot 3 多模块单体、PostgreSQL / Flyway、JDBC 会话、CSRF 和公司/项目权限。
- 成员与项目管理、密码与会话管理、登录限流；已有全局账号不能被其他公司直接拉入或重置密码。
- 任务 CRUD、版本、任务树、DAG、状态机、实际与剩余工时。
- 技能认证、工作日历、容量桶、占用明细、真实 PostgreSQL 并发与对账约束。
- AI 分析运行、结构化校验、持久化调用预算、上下文缓存、可靠作业、Outbox/通知与审计。
- AI 建议经人工确认后生成叶子任务和依赖，并发起排期模拟；模型不能确认自己的假设。
- 日级确定性排期：技能与成员过滤、跨日分配、满额换人、FS 依赖次日起排、继承硬截止、锁定及下游影响范围。
- 模拟不占用容量；方案确认时检查权限、版本、约束和日期，并在同一事务中更新任务和容量。
- 当天分配校验与开工标记；完成/取消保留历史及今日已开始占用，释放未来可移动占用。
- 成员排期、方案冲突、计划提交幂等，以及完整应用的 PostgreSQL 流程测试。

接口与操作流程见 [集成使用说明](docs/integrated-workflow.md)。

模型接入见 [DeepSeek / GLM 标准 API 接入指南](docs/model-integration.md)，包括本地密钥配置、Docker 环境变量和显式单次联调命令 `./scripts/check-ai.sh --live`。

## 当前边界

已提供 Vue 3 中文前端，覆盖任务、AI 建议、成员技能与容量、周排期和项目设置；附件安全解析、CSV 导入、已有全局账号的邀请接受流程尚未提供。已有账号直接添加会明确拒绝，不会悄悄建立成员关系。AI 默认关闭；无模型配置时生成明确失败的分析运行，可人工填写工时并继续排期。实际供应商的估时准确率和拆分质量需要业务样本验证。

排期采用最多 200 个影响任务、180 天窗口、宽度 16 的确定性搜索；无方案只表示当前搜索范围内未找到。建议确认、排期方案提交是两个显式动作；当前不开放无人审批的 AI 自动提交。数据库变更采用新增迁移，不修改基础 V1/V2。

## 前端工作台

在后端启动后执行 `./scripts/frontend.sh`，访问 [http://localhost:5173](http://localhost:5173)。登录使用 `.env` 中的演示账号。详见 [前端使用与部署说明](docs/frontend.md)。

## 快速启动

前提：JDK 21、Docker Desktop/Engine 已启动。macOS 上 `scripts/mvn.sh` 与 `scripts/dev.sh` 自动选用已安装的 JDK 21，不改变全局 Java 设置；其他平台需将 `JAVA_HOME` 指向 JDK 21。

```bash
cp .env.example .env
# 按需修改 .env 中的数据库和演示账号密码（至少 12 字符）。
./scripts/dev.sh
```

脚本启动 PostgreSQL、构建应用并在前台启动。Ctrl+C 停止应用，数据库保留；`docker compose stop postgres` 只停止本项目数据库，不删除数据。

- 健康检查：[http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- Swagger UI：[http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- OpenAPI：[http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- 数据库默认宿主端口：54329，避免占用系统默认 5432。
- 演示账号由 `.env` 的 `BOOTSTRAP_USERNAME` / `BOOTSTRAP_PASSWORD` 指定；首次启动创建，后续不重置。
- 演示公司：`11111111-1111-4111-8111-111111111111`。
- 演示项目：`22222222-2222-4222-8222-222222222222`。

也可全部用容器运行：

```bash
docker compose --profile app up --build -d
docker compose logs -f app
```

Compose 中的 app 是**本地开发配置**，宿主端口仅绑定 127.0.0.1。生产不启用 dev profile，不启用演示初始化和公开文档，使用 HTTPS 与独立凭据。默认应用配置没有后门账号，也没有内置数据库密码。

## 调用接口

登录也是写操作，须先获取 CSRF token。浏览器/客户端保存会话 Cookie；登录成功后重新获取 token，再执行后续写操作。

```bash
# 1. 获取 CSRF；把 data.token 复制到下一步的 YOUR_TOKEN。
curl -c /tmp/scheduler-cookies.txt http://localhost:8080/api/v1/auth/csrf

# 2. 密码用你自己的本地 .env 配置替换，不把真实密码写入共享脚本。
curl -b /tmp/scheduler-cookies.txt -c /tmp/scheduler-cookies.txt \
  -H 'Content-Type: application/json' -H 'X-CSRF-TOKEN: YOUR_TOKEN' \
  -d '{"username":"admin","password":"YOUR_LOCAL_PASSWORD"}' \
  http://localhost:8080/api/v1/auth/login

# 3. 当前用户公司、项目；公司头必须属于当前登录用户。
curl -b /tmp/scheduler-cookies.txt http://localhost:8080/api/v1/me/companies
curl -b /tmp/scheduler-cookies.txt \
  -H 'X-Company-Id: 11111111-1111-4111-8111-111111111111' \
  http://localhost:8080/api/v1/projects
```

接口契约和限制详见 [基础接口说明](docs/foundation-api.md)。Swagger 默认可读，写接口同样受 CSRF 保护。

## 构建与验证

```bash
# 单元测试与构建，不需要 Docker。
./scripts/mvn.sh -B -ntp verify

# 真实 PostgreSQL 17 集成测试，需要 Docker；容器和测试数据库自动销毁。
./scripts/mvn.sh -B -ntp -Pintegration verify

# 只构建一个业务模块及依赖。
./scripts/mvn.sh -B -ntp -pl scheduler-planning -am verify
```

`-Pintegration` 不会在 Docker 不可用时悄悄跳过。普通 `verify` 不运行 `*IT`。Maven Enforcer 会拒绝 Java 8/17/25，确保所有分支一致使用 21。

历史基础验证见 [验证记录](docs/verification.md)；本轮集成结果以 `docs/reviews/fix-verification-2026-10-05.md` 为准。

可执行 JAR：`scheduler-app/target/scheduler-app-0.1.0-SNAPSHOT.jar`。直接启动时通过环境设置 `DB_URL`、`DB_USER`、`DB_PASSWORD`；Maven 和 Java 不会自动读取 `.env`，只有 Compose 和 `scripts/dev.sh` 会读取。

## 项目结构

```text
frontend/             Vue 3 + TypeScript 中文管理前端
scheduler-common/     共享响应、异常、Web 基础设施、跨模块契约
scheduler-identity/   工具 A：身份、公司、成员、项目、权限
scheduler-task/       工具 B：任务、依赖、附件、工时与验收
scheduler-planning/   工具 C：技能、日历、容量、排期、冲突
scheduler-agent/      工具 D：AI 编排、作业、Outbox、通知、审计
scheduler-app/        统一启动、装配、配置、基础迁移、跨模块集成测试
docs/                开发分工、接口说明、可直接交给 AI 工具的任务书
scripts/             构建与本地启动
prd.md / trd.md       产品与技术需求
```

模块依赖：四个业务模块只依赖 common；app 依赖四个模块。禁止业务模块直接引用另一个模块的 Repository。共享契约变动由主分支协调，避免四路同时修改根 POM、公共 DTO 或启动配置。

## 四个 AI 工具如何开始

先读 [并行开发指南](docs/parallel-development.md)，然后把对应任务书交给各工具：

1. [A：组织与权限](docs/tasks/ai-a-identity.md)
2. [B：任务与依赖](docs/tasks/ai-b-task.md)
3. [C：容量与排期](docs/tasks/ai-c-planning.md)
4. [D：AI 与异步基础设施](docs/tasks/ai-d-agent.md)

每个工具使用独立 checkout/worktree 和测试数据库。仅创建不同分支名称、仍共享同一工作目录，无法实现安全并行开发。

版本选择参考：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[springdoc 兼容说明](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)。当前依赖版本已固定；后续升级通过独立变更与完整集成测试完成。

# AI Agent 项目排期管理系统

基于 **Java 21 + Spring Boot 3.5.16 + PostgreSQL 17** 的后端基础工程。采用 Maven 多模块组织代码，最终部署为一个应用，供四个 AI 工具在独立分支/工作目录中继续开发。

## 当前已经实现

- Maven Wrapper 3.9.11、JDK 21 构建校验、六个模块和统一依赖版本。
- PostgreSQL / Flyway 基础表、复合外键、JDBC 持久化会话。
- JSON 账号密码登录、CSRF、会话 ID 轮换、退出登录。
- 当前用户公司列表、按公司与项目成员权限过滤的项目列表。
- 统一响应、异常处理、requestId、健康检查、开发环境 OpenAPI / Swagger UI。
- 开发环境演示账号/公司/项目初始化，不覆盖已有密码。
- 纯函数容量计算、任务状态枚举、跨模块接口、明确报错的未配置模型适配器。
- Docker Compose、Dockerfile、CI，以及真实 PostgreSQL 集成测试。

## 尚未实现

任务 CRUD、附件/CSV、技能日历管理、容量账本、DAG 和排期引擎、计划提交事务、真实模型调用、可靠作业、审计和通知由四个业务分支接续实现。接口声明不表示实现已经完成；未实现功能没有伪造成功响应。当前没有前端。

这是一份开发基线，尚未完成生产账号管理、登录限流、完整 RBAC、密码找回和业务安全验收，不能作为完整生产系统直接上线。

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

本次基础验证共 13 项测试通过，详见 [验证记录](docs/verification.md)。

可执行 JAR：`scheduler-app/target/scheduler-app-0.1.0-SNAPSHOT.jar`。直接启动时通过环境设置 `DB_URL`、`DB_USER`、`DB_PASSWORD`；Maven 和 Java 不会自动读取 `.env`，只有 Compose 和 `scripts/dev.sh` 会读取。

## 项目结构

```text
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

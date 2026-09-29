# 基础工程验证记录

验证日期：2026-09-29。运行环境：macOS ARM64、JDK 21.0.9、Maven Wrapper 3.9.11、Docker 28.3.3；Testcontainers 启动 PostgreSQL 17 测试容器。

执行命令：

```bash
./scripts/mvn.sh -B -ntp -Pintegration verify
bash -n scripts/*.sh
DB_PASSWORD=compose-validation-only BOOTSTRAP_PASSWORD=compose-validation-only docker compose --profile app config --quiet
```

结果：所有模块构建成功，**13 项测试通过，0 失败、0 错误、0 跳过**。

| 测试类 | 数量 | 验证内容 |
| --- | --- | --- |
| CapacityPolicyTest | 4 | 请假与缓冲扣减、容量向下/工时向上取整、不可用日期、非法值与溢出 |
| DisabledAiModelGatewayTest | 1 | 未配置模型明确报错，不生成虚假估算 |
| FoundationIT | 8 | 迁移与重复执行、HTTP 登录与 JDBC 会话、CSRF/参数校验、真实其他公司隔离、退出、禁用成员、数据库约束、OpenAPI |

集成测试启动真实 HTTP 应用和 PostgreSQL，测试容器完成后自动销毁。POM XML、文档本地链接、Shell 语法和 Compose 配置已校验。

验证范围是基础工程：尚未实现的排期并发抢占、大任务拆解、AI 供应商连通与完整业务验收不在本次通过范围内。Dockerfile 已提供，本次未构建最终容器镜像；Compose 配置已验证。首次启动仍需用户按 README 准备本地 `.env`。

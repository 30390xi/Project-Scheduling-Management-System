package com.company.scheduler.identity;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** identity 模块独立集成测试入口：装配 common + identity，验证真实 PostgreSQL 下的权限与认证行为。 */
@SpringBootApplication(scanBasePackages = "com.company.scheduler")
public class IdentityTestApplication {
}

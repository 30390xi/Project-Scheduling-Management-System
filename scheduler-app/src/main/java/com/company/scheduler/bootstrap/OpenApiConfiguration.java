package com.company.scheduler.bootstrap;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("AI 项目排期系统基础 API").version("0.1.0")
                .description("会话认证；写请求须先 GET /api/v1/auth/csrf，并携带返回的 token。业务接口按模块逐步实现。"));
    }
}

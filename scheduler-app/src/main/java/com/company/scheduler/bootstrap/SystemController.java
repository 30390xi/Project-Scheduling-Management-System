package com.company.scheduler.bootstrap;

import com.company.scheduler.shared.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    @GetMapping("/api/v1/system/info")
    public ApiResponse<SystemInfo> info() {
        return ApiResponse.of(new SystemInfo("AI Project Scheduler", "0.1.0-SNAPSHOT", "foundation",
                List.of("session-auth", "company-access", "project-list", "database-migrations", "openapi")));
    }

    public record SystemInfo(String name, String version, String stage, List<String> implementedCapabilities) {}
}

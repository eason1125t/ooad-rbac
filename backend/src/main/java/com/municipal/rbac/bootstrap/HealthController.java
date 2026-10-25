/** 功能：公开存活探针；仅报告进程存活，不将数据库或Redis健康包装为已验证。 */
package com.municipal.rbac.bootstrap;

import com.municipal.rbac.security.PublicEndpoint;
import com.municipal.rbac.shared.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    @PublicEndpoint
    public ApiResponse<?> health() {
        return ApiResponse.of(Map.of("status", "UP"));
    }
}

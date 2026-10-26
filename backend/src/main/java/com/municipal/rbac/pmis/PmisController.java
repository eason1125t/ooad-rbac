/** 功能：保留PMIS兼容鉴权探针；真实立项流程由PmisProjectBusinessController实现，不侵入授权核心。 */
package com.municipal.rbac.pmis;

import com.municipal.rbac.shared.ApiResponse;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class PmisController {
    @GetMapping("/api/v1/pmis/demo")
    @PreAuthorize("@permissionBridge.allowed(authentication, 'pmis', 'pmis:project:view')")
    public ApiResponse<?> demo() {
        return ApiResponse.of(
                Map.of(
                        "application",
                        "pmis",
                        "status",
                        "SKELETON",
                        "message",
                        "兼容鉴权探针已通过，立项业务使用projects接口"));
    }
}

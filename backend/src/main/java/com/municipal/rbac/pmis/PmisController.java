/** 功能：工程项目真实权限接入演示；后续项目状态和本人归属在本模块实现，不侵入权限核心。 */
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
                Map.of("application", "pmis", "status", "SKELETON", "message", "授权链路已接通，项目业务尚未实现"));
    }
}

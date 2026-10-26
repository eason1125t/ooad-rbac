/** 功能：管理端受保护演示入口；仅验证统一权限接入，无角色管理或人员CRUD。 */
package com.municipal.rbac.bootstrap;

import com.municipal.rbac.shared.ApiResponse;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminDemoController {
    @GetMapping("/api/v1/admin/demo")
    @PreAuthorize("@permissionBridge.allowed(authentication, 'admin', 'admin:console:view')")
    public ApiResponse<?> demo() {
        return ApiResponse.of(
                Map.of(
                        "application",
                        "admin",
                        "status",
                        "SKELETON",
                        "message",
                        "兼容鉴权探针已通过，管理业务使用admin工作区接口"));
    }
}

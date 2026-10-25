/** 功能：OA真实权限接入演示；不实现公文CRUD，不读取权限内部Mapper，不返回伪造业务数据。 */
package com.municipal.rbac.oa;

import com.municipal.rbac.shared.ApiResponse;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class OaController {
    @GetMapping("/api/v1/oa/demo")
    @PreAuthorize("@permissionBridge.allowed(authentication, 'oa', 'oa:document:view')")
    public ApiResponse<?> demo() {
        return ApiResponse.of(
                Map.of("application", "oa", "status", "SKELETON", "message", "授权链路已接通，公文业务尚未实现"));
    }
}

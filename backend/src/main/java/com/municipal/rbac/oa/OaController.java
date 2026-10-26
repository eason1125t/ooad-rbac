/** 功能：保留OA兼容鉴权探针；真实公文流程由OaDocumentBusinessController提供，不读取授权内部。 */
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
                Map.of(
                        "application",
                        "oa",
                        "status",
                        "SKELETON",
                        "message",
                        "兼容鉴权探针已通过，公文业务使用documents接口"));
    }
}

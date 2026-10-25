/** 功能：统一授权输入；供security适配和未来资源范围使用，核心不依赖Servlet或业务表。 */
package com.municipal.rbac.authorization;

import java.util.Map;

public record PermissionContext(
        long subjectId,
        String sessionId,
        String application,
        String operation,
        Map<String, String> resource) {
    public PermissionContext {
        resource = resource == null ? Map.of() : Map.copyOf(resource);
    }
}

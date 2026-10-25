/** 功能：统一操作授权入口；检查应用与权限码一致，授权来源只交给PermissionResolver。 */
package com.municipal.rbac.authorization;

import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class PermissionChecker {
    private final PermissionResolver resolver;
    private final Clock clock;

    public PermissionChecker(PermissionResolver resolver, Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    public boolean allows(PermissionContext context) {
        return context.subjectId() > 0
                && context.sessionId() != null
                && context.operation().startsWith(context.application() + ":")
                && resolver.resolve(context.subjectId(), clock.instant())
                        .contains(context.operation());
    }
}

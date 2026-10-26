/** 功能：统一操作授权入口；检查应用与权限码一致，授权来源只交给PermissionResolver。 */
package com.municipal.rbac.authorization;

import java.time.Clock;
import java.util.Set;
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

    public boolean allowsAll(PermissionContext context, Set<String> operations) {
        if (context.subjectId() <= 0
                || context.sessionId() == null
                || operations.isEmpty()
                || operations.stream().anyMatch(op -> !op.startsWith(context.application() + ":")))
            return false;
        return resolver.resolve(context.subjectId(), clock.instant()).containsAll(operations);
    }

    public boolean allowsManaged(PermissionContext context, long applicationId) {
        if (context.subjectId() <= 0
                || context.sessionId() == null
                || !context.operation().startsWith("admin:")) return false;
        return allowsAll(context, Set.of(context.operation(), "admin:platform:manage"))
                || resolver.managedApplications(
                                context.subjectId(), context.operation(), clock.instant())
                        .contains(applicationId);
    }

    public boolean managementConsole(PermissionContext context) {
        return allowsAll(context, Set.of(context.operation(), "admin:platform:manage"))
                || (context.subjectId() > 0
                        && context.sessionId() != null
                        && context.operation().startsWith("admin:")
                        && !resolver.managedApplications(
                                        context.subjectId(), context.operation(), clock.instant())
                                .isEmpty());
    }
}

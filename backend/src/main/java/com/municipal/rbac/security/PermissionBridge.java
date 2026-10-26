/** 功能：将Spring方法授权的主体转换为核心PermissionContext；不接受客户端userId，也不保存权限快照。 */
package com.municipal.rbac.security;

import com.municipal.rbac.authorization.PermissionChecker;
import com.municipal.rbac.authorization.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("permissionBridge")
public class PermissionBridge {
    private final PermissionChecker checker;
    private final StringRedisTemplate redis;
    private final HttpServletRequest request;

    public PermissionBridge(
            PermissionChecker checker, StringRedisTemplate redis, HttpServletRequest request) {
        this.checker = checker;
        this.redis = redis;
        this.request = request;
    }

    public boolean allowed(Authentication authentication, String application, String operation) {
        return allowed(authentication, application, operation, false);
    }

    public boolean platform(Authentication authentication, String operation) {
        return allowed(authentication, "admin", operation, true);
    }

    public boolean managed(Authentication authentication, String applicationId, String operation) {
        PermissionContext c = context(authentication, operation);
        if (c == null) return false;
        if (checker.allowsAll(c, Set.of(operation, "admin:platform:manage"))) return true;
        try {
            return checker.allowsManaged(
                    c, com.municipal.rbac.identity.IdentityData.id(applicationId));
        } catch (com.municipal.rbac.shared.DomainFailure invalid) {
            return false;
        }
    }

    public boolean managementConsole(Authentication authentication, String operation) {
        PermissionContext c = context(authentication, operation);
        return c != null && checker.managementConsole(c);
    }

    private PermissionContext context(Authentication authentication, String operation) {
        if (!(authentication.getPrincipal() instanceof IdentityPrincipal p)
                || request.getSession(false) == null) return null;
        String pong =
                redis.execute(
                        (org.springframework.data.redis.core.RedisCallback<String>)
                                connection -> connection.ping());
        if (!"PONG".equals(pong))
            throw new org.springframework.data.redis.RedisConnectionFailureException(
                    "Redis state unavailable");
        return new PermissionContext(
                p.id(), request.getSession(false).getId(), "admin", operation, Map.of());
    }

    private boolean allowed(
            Authentication authentication, String application, String operation, boolean platform) {
        if (!(authentication.getPrincipal() instanceof IdentityPrincipal principal)
                || request.getSession(false) == null) return false;
        // Redis状态不可确认时不执行受保护操作；一次请求一次探测，不逐权限点访问。
        String pong =
                redis.execute(
                        (org.springframework.data.redis.core.RedisCallback<String>)
                                connection -> connection.ping());
        if (!"PONG".equals(pong))
            throw new org.springframework.data.redis.RedisConnectionFailureException(
                    "Redis state unavailable");
        var context =
                new PermissionContext(
                        principal.id(),
                        request.getSession(false).getId(),
                        application,
                        operation,
                        Map.of());
        if (!platform
                && application.equals("admin")
                && operation.equals("admin:console:view")
                && checker.managementConsole(
                        new PermissionContext(
                                principal.id(),
                                request.getSession(false).getId(),
                                "admin",
                                "admin:application:view",
                                Map.of()))) return true;
        return platform
                ? checker.allowsAll(context, Set.of(operation, "admin:platform:manage"))
                : checker.allows(context);
    }
}

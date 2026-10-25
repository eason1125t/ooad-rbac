/** 功能：将Spring方法授权的主体转换为核心PermissionContext；不接受客户端userId，也不保存权限快照。 */
package com.municipal.rbac.security;

import com.municipal.rbac.authorization.PermissionChecker;
import com.municipal.rbac.authorization.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
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
        return checker.allows(
                new PermissionContext(
                        principal.id(),
                        request.getSession(false).getId(),
                        application,
                        operation,
                        Map.of()));
    }
}

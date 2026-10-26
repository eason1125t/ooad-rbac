/** 功能：CSRF令牌与当前主体接口；使用服务端认证上下文和统一Resolver，匹配OpenAPI认证分区。 */
package com.municipal.rbac.security;

import com.municipal.rbac.authorization.PermissionResolver;
import com.municipal.rbac.identity.AccountMapper;
import com.municipal.rbac.shared.ApiResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final PermissionResolver resolver;
    private final Clock clock;
    private final AccountMapper accounts;

    public AuthController(PermissionResolver resolver, Clock clock, AccountMapper accounts) {
        this.resolver = resolver;
        this.clock = clock;
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    @PublicEndpoint
    public ApiResponse<?> csrf(CsrfToken token) {
        return ApiResponse.of(
                Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    @GetMapping("/me")
    @AuthenticatedEndpoint
    public ApiResponse<?> me(@AuthenticationPrincipal IdentityPrincipal principal) {
        return ApiResponse.of(
                Map.of(
                        "id",
                        Long.toString(principal.id()),
                        "username",
                        principal.getUsername(),
                        "forcePasswordChange",
                        accounts.byId(principal.id()).forcePasswordChange(),
                        "permissions",
                        resolver.resolve(principal.id(), clock.instant()).stream()
                                .sorted()
                                .toList()));
    }
}

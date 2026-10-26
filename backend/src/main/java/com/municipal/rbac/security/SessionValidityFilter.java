/** 功能：每请求验证绝对截止与账号撤销版本；身份无效时撤销服务端会话，权限计算仍由方法授权负责。 */
package com.municipal.rbac.security;

import com.municipal.rbac.identity.Account;
import com.municipal.rbac.identity.AccountMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidityFilter extends OncePerRequestFilter {
    private final AccountMapper accounts;
    private final SessionPolicy policy;
    private final JsonSecurityErrors errors;

    public SessionValidityFilter(
            AccountMapper accounts, SessionPolicy policy, JsonSecurityErrors errors) {
        this.accounts = accounts;
        this.policy = policy;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof IdentityPrincipal principal) {
            HttpSession session = request.getSession(false);
            Account account = accounts.byId(principal.id());
            if (session == null
                    || !policy.valid(session.getAttribute(SessionPolicy.AUTHENTICATED_AT))
                    || account == null
                    || !account.enabled()
                    || account.authVersion() != principal.authVersion()) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                errors.write(request, response, 401, "UNAUTHENTICATED", "登录已失效");
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (account.forcePasswordChange()
                    && !Set.of(
                                    "/api/v1/auth/me",
                                    "/api/v1/auth/csrf",
                                    "/api/v1/auth/password",
                                    "/api/v1/auth/logout")
                            .contains(path)) {
                errors.write(request, response, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

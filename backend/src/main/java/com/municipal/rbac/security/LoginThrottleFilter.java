/** 功能：仅登录POST的前置限流；不记录请求凭据，设施故障交给最外层统一503出口。 */
package com.municipal.rbac.security;

import com.municipal.rbac.shared.DomainFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginThrottleFilter extends OncePerRequestFilter {
    private final LoginThrottle throttle;
    private final JsonSecurityErrors errors;

    public LoginThrottleFilter(LoginThrottle throttle, JsonSecurityErrors errors) {
        this.throttle = throttle;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getMethod().equals("POST")
                && request.getRequestURI()
                        .equals(request.getContextPath() + "/api/v1/auth/login")) {
            try {
                throttle.check(request.getParameter("username"), request.getRemoteAddr());
            } catch (DomainFailure limited) {
                errors.write(
                        request, response, limited.status(), limited.code(), limited.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

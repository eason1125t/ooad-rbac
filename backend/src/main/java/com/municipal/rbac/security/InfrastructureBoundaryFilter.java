/** 功能：在Redis Session过滤器外生成traceId并适配设施异常；避免设施失败伪装403或泄露堆栈。 */
package com.municipal.rbac.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

public class InfrastructureBoundaryFilter extends OncePerRequestFilter {
    private final JsonSecurityErrors errors;

    public InfrastructureBoundaryFilter(JsonSecurityErrors errors) {
        this.errors = errors;
    }

    public static boolean infrastructure(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof DataAccessException
                    || cursor instanceof InternalAuthenticationServiceException) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = UUID.randomUUID().toString();
        request.setAttribute("traceId", trace);
        response.setHeader("X-Trace-Id", trace);
        MDC.put("traceId", trace);
        // 小型JSON响应先缓冲，Session保存失败时仍能改为503，防止成功体先发给浏览器。
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, buffered);
        } catch (ServletException | RuntimeException failure) {
            if (!infrastructure(failure)) throw failure;
            if (!buffered.isCommitted()) {
                buffered.resetBuffer();
                errors.write(request, buffered, 503, "DEPENDENCY_UNAVAILABLE", "必要服务暂不可用");
            }
        } finally {
            MDC.remove("traceId");
        }
        buffered.copyBodyToResponse();
    }
}

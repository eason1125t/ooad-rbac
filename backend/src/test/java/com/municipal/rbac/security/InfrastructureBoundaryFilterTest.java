/** 功能：验证Redis会话保存异常发生在响应写入后仍返回503；非设施异常不得被伪装为权限不足。 */
package com.municipal.rbac.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.*;

class InfrastructureBoundaryFilterTest {
    @Test
    void lateSessionFailureReplacesBufferedSuccessBody() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        var response = new MockHttpServletResponse();
        var filter = new InfrastructureBoundaryFilter(new JsonSecurityErrors(new ObjectMapper()));
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    res.getWriter().write("{\"data\":\"must not leak\"}");
                    res.flushBuffer();
                    throw new RedisConnectionFailureException("synthetic secret");
                });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("DEPENDENCY_UNAVAILABLE")
                .doesNotContain("must not leak", "synthetic secret");
        assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void unrelatedFailureIsRethrown() {
        var filter = new InfrastructureBoundaryFilter(new JsonSecurityErrors(new ObjectMapper()));
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        (req, res) -> {
                                            throw new IllegalStateException("test");
                                        }))
                .isInstanceOf(IllegalStateException.class);
    }
}

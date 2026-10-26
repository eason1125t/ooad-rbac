/** 功能：运行真实Spring Security/方法授权验证最小链路；仅替换数据库/Redis，不宣称设施集成通过。 */
package com.municipal.testing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.municipal.rbac.authorization.*;
import com.municipal.rbac.bootstrap.AdminDemoController;
import com.municipal.rbac.bootstrap.HealthController;
import com.municipal.rbac.identity.*;
import com.municipal.rbac.oa.OaController;
import com.municipal.rbac.pmis.PmisController;
import com.municipal.rbac.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@WebMvcTest
@ContextConfiguration(classes = SecurityChainTest.TestApplication.class)
class SecurityChainTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        SecurityConfig.class,
        EndpointRules.class,
        JsonSecurityErrors.class,
        SessionPolicy.class,
        PermissionBridge.class,
        PermissionChecker.class,
        AuthController.class,
        ApiExceptionHandler.class,
        HealthController.class,
        AdminDemoController.class,
        OaController.class,
        PmisController.class,
        TestEndpoints.class
    })
    static class TestApplication {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean AccountMapper accounts;
    @MockitoBean PermissionResolver resolver;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean LoginThrottle throttle;
    private Account account;
    private static final String TEST_PASSWORD = "1234";

    @BeforeEach
    void setup() {
        account = new Account(1, "super-admin", encoder.encode(TEST_PASSWORD), true, 0);
        when(accounts.byUsername("super-admin")).thenReturn(account);
        when(accounts.byId(1)).thenReturn(account);
        when(resolver.resolve(eq(1L), any()))
                .thenReturn(Set.of("oa:document:view", "pmis:project:view", "admin:console:view"));
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
    }

    private record Client(MockHttpSession session, String csrf) {}

    private Client csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Client(
                (MockHttpSession) result.getRequest().getSession(),
                json.readTree(result.getResponse().getContentAsString())
                        .path("data")
                        .path("token")
                        .asText());
    }

    private Client login() throws Exception {
        Client client = csrf(null);
        String anonymousId = client.session().getId();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(client.session())
                                .header("X-CSRF-TOKEN", client.csrf())
                                .param("username", "super-admin")
                                .param("password", TEST_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true));
        assertThat(client.session().getId()).isNotEqualTo(anonymousId);
        var context =
                (org.springframework.security.core.context.SecurityContext)
                        client.session().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((IdentityPrincipal) context.getAuthentication().getPrincipal()).getPassword())
                .isNull();
        return csrf(client.session());
    }

    @Test
    void logoutRevokesOnlyTheCurrentSession() throws Exception {
        Client first = login();
        Client second = login();
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .session(first.session())
                                .header("X-CSRF-TOKEN", first.csrf()))
                .andExpect(status().isOk());
        assertThat(first.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me").session(second.session())).andExpect(status().isOk());
    }

    @Test
    void loginMeThreePermissionsAndLogoutWithActualCsrfToken() throws Exception {
        Client client = login();
        mvc.perform(get("/api/v1/auth/me").session(client.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("1"));
        for (String app : List.of("admin", "oa", "pmis"))
            mvc.perform(get("/api/v1/" + app + "/demo").session(client.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.application").value(app));
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .session(client.session())
                                .header("X-CSRF-TOKEN", client.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false));
        assertThat(client.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedLowPermissionAndDefaultDeny() throws Exception {
        mvc.perform(get("/api/v1/oa/demo")).andExpect(status().isUnauthorized());
        Client client = login();
        when(resolver.resolve(eq(1L), any())).thenReturn(Set.of());
        mvc.perform(get("/api/v1/oa/demo").session(client.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/test/undeclared").session(client.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/not-defined").session(client.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingAndIncorrectCsrfAndInvalidCredentials() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .param("username", "super-admin")
                                .param("password", TEST_PASSWORD))
                .andExpect(status().isForbidden());
        Client client = csrf(null);
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(client.session())
                                .header("X-CSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(client.session())
                                .header("X-CSRF-TOKEN", client.csrf())
                                .param("username", "super-admin")
                                .param("password", "invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void redisAndDatabaseFailureReturn503AndHealthIsPublic() throws Exception {
        Client client = login();
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("synthetic secret"));
        mvc.perform(get("/api/v1/oa/demo").session(client.session()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "synthetic secret"))));
        when(accounts.byId(1))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "super-admin"));
        mvc.perform(get("/api/v1/auth/me").session(client.session()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void absoluteExpiryAndAccountRevocationAreServerSide() throws Exception {
        Client client = login();
        client.session()
                .setAttribute(
                        SessionPolicy.AUTHENTICATED_AT, NOW.minusSeconds(7200).toEpochMilli());
        mvc.perform(get("/api/v1/auth/me").session(client.session()))
                .andExpect(status().isUnauthorized());
        assertThat(client.session().isInvalid()).isTrue();
        Client second = login();
        when(accounts.byId(1))
                .thenReturn(new Account(1, "super-admin", account.passwordHash(), true, 1));
        mvc.perform(get("/api/v1/auth/me").session(second.session()))
                .andExpect(status().isUnauthorized());
        when(accounts.byId(1)).thenReturn(account);
        Client third = login();
        when(accounts.byId(1))
                .thenReturn(new Account(1, "super-admin", account.passwordHash(), false, 0));
        mvc.perform(get("/api/v1/auth/me").session(third.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permissionExpressionBindsMethodParameters() throws Exception {
        Client client = login();
        mvc.perform(get("/api/v1/test/permission/oa").session(client.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/test/permission/pmis").session(client.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void allProductionApiMethodsDeclareExactlyOneRule() {
        mappings.getHandlerMethods()
                .forEach(
                        (mapping, method) -> {
                            if (!method.getBeanType().getPackageName().startsWith("com.municipal")
                                    || method.getBeanType() == TestEndpoints.class) return;
                            int count = 0;
                            for (Class<? extends java.lang.annotation.Annotation> annotation :
                                    List.of(
                                            PublicEndpoint.class,
                                            AuthenticatedEndpoint.class,
                                            PreAuthorize.class))
                                if (AnnotatedElementUtils.hasAnnotation(
                                        method.getMethod(), annotation)) count++;
                            assertThat(count).as(method.toString()).isEqualTo(1);
                        });
    }

    @RestController
    static class TestEndpoints {
        @GetMapping("/api/v1/test/undeclared")
        public String undeclared() {
            return "must not be reached";
        }

        @GetMapping("/api/v1/test/permission/{application}")
        @PreAuthorize("@permissionBridge.allowed(authentication, #application, 'oa:document:view')")
        public String parameter(@PathVariable String application) {
            return application;
        }
    }
}

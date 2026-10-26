/** 功能：唯一安全链；连接身份认证、CSRF、Session和方法权限；异常由专用安全处理器返回JSON。 */
package com.municipal.rbac.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.municipal.rbac.identity.AccountMapper;
import com.municipal.rbac.identity.IdentityData;
import com.municipal.rbac.identity.PasswordHasher;
import com.municipal.rbac.shared.ApiResponse;
import com.municipal.rbac.shared.DomainFailure;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain healthChain(HttpSecurity http) throws Exception {
        // 存活检查不读取Session或身份数据库，即使浏览器携带失效Cookie也能工作。
        return http.securityMatcher("/api/v1/health")
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new org.springframework.security.web.context
                                                .NullSecurityContextRepository()))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(
                                        org.springframework.security.config.http
                                                .SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    PasswordHasher identityPasswords(PasswordEncoder encoder) {
        return new PasswordHasher() {
            public String encode(String password) {
                return encoder.encode(password);
            }

            public boolean matches(String password, String hash) {
                return encoder.matches(password, hash);
            }
        };
    }

    @Bean
    LoginThrottle loginThrottle(StringRedisTemplate redis) {
        return new LoginThrottle(redis);
    }

    @Bean
    UserDetailsService userDetails(AccountMapper accounts) {
        return username -> {
            String normalized;
            try {
                normalized = IdentityData.code(username);
            } catch (DomainFailure failure) {
                throw new UsernameNotFoundException("invalid credentials");
            }
            var account = accounts.byUsername(normalized);
            if (account == null) throw new UsernameNotFoundException("invalid credentials");
            return new IdentityPrincipal(account);
        };
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(
            UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return provider;
    }

    @Bean
    FilterRegistrationBean<InfrastructureBoundaryFilter> infrastructureBoundary(
            JsonSecurityErrors errors) {
        var registration = new FilterRegistrationBean<>(new InfrastructureBoundaryFilter(errors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityChain(
            HttpSecurity http,
            EndpointRules rules,
            AccountMapper accounts,
            SessionPolicy policy,
            JsonSecurityErrors errors,
            LoginThrottle throttle,
            ObjectMapper json)
            throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                                        .permitAll()
                                        .anyRequest()
                                        .access(rules))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (req, res, failure) ->
                                                        errors.write(
                                                                req,
                                                                res,
                                                                401,
                                                                "UNAUTHENTICATED",
                                                                "请先登录"))
                                        .accessDeniedHandler(
                                                (req, res, failure) ->
                                                        errors.write(
                                                                req,
                                                                res,
                                                                403,
                                                                "FORBIDDEN",
                                                                "无访问权限或CSRF令牌无效")))
                .formLogin(
                        form ->
                                form.loginPage("/api/v1/auth/login")
                                        .loginProcessingUrl("/api/v1/auth/login")
                                        .successHandler(
                                                (req, res, authentication) -> {
                                                    throttle.succeeded(
                                                            req.getParameter("username"));
                                                    req.getSession()
                                                            .setAttribute(
                                                                    SessionPolicy.AUTHENTICATED_AT,
                                                                    policy.nowMillis());
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    json.writeValue(
                                                            res.getOutputStream(),
                                                            ApiResponse.of(
                                                                    Map.of("authenticated", true)));
                                                })
                                        .failureHandler(
                                                (req, res, failure) -> {
                                                    boolean unavailable =
                                                            InfrastructureBoundaryFilter
                                                                    .infrastructure(failure);
                                                    if (!unavailable)
                                                        throttle.failed(
                                                                req.getParameter("username"));
                                                    errors.write(
                                                            req,
                                                            res,
                                                            unavailable ? 503 : 401,
                                                            unavailable
                                                                    ? "DEPENDENCY_UNAVAILABLE"
                                                                    : "INVALID_CREDENTIALS",
                                                            unavailable ? "必要服务暂不可用" : "账号或密码错误");
                                                }))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/v1/auth/logout")
                                        .deleteCookies("SESSION")
                                        .logoutSuccessHandler(
                                                (req, res, authentication) -> {
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    json.writeValue(
                                                            res.getOutputStream(),
                                                            ApiResponse.of(
                                                                    Map.of(
                                                                            "authenticated",
                                                                            false)));
                                                }))
                .requestCache(cache -> cache.disable())
                .sessionManagement(
                        session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .addFilterBefore(
                        new LoginThrottleFilter(throttle, errors),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new SessionValidityFilter(accounts, policy, errors),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}

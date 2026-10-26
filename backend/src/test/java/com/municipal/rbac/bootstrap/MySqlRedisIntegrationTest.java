/** 功能：Docker可用时从空库迁移并以真实MySQL/Redis验证HTTP安全链；不可用时明确跳过，不使用H2。 */
package com.municipal.rbac.bootstrap;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.municipal.rbac.authorization.PermissionResolver;
import java.net.*;
import java.net.http.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        classes = RbacApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("demo")
class MySqlRedisIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4.7"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.6-alpine"))
                    .withExposedPorts(6379);

    static final String PASSWORD = "1234";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.bootstrap.username", () -> "");
        registry.add("app.bootstrap.password", () -> "");
        registry.add("spring.session.timeout", () -> "2s");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PermissionResolver resolver;
    private HttpClient client;

    @BeforeEach
    void client() {
        client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String csrf() throws Exception {
        return json.readTree(get("/api/v1/auth/csrf").body()).path("data").path("token").asText();
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-CSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void login(String username) throws Exception {
        assertThat(
                        post(
                                        "/api/v1/auth/login",
                                        "username=" + username + "&password=" + PASSWORD,
                                        csrf())
                                .statusCode())
                .isEqualTo(200);
    }

    @Test
    void realSessionChainAndCookieAttributes() throws Exception {
        assertThat(get("/api/v1/oa/demo").statusCode()).isEqualTo(401);
        login("super-admin");
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(200);
        for (String app : List.of("admin", "oa", "pmis"))
            assertThat(get("/api/v1/" + app + "/demo").statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/auth/logout", "", csrf()).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(401);
        var cookieResponse =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(cookieResponse.headers().allValues("set-cookie").toString())
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void realRedisIdleExpiryRejectsOldCookie() throws Exception {
        login("super-admin");
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(200);
        Thread.sleep(2200);
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void lowPrivilegeUserIsDeniedAndDisabledSessionRevoked() throws Exception {
        login("employee-1");
        assertThat(get("/api/v1/oa/demo").statusCode()).isEqualTo(403);
        jdbc.update(
                "UPDATE id_user SET enabled=FALSE,auth_version=auth_version+1 WHERE username=?",
                "employee-1");
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void businessDemoAccountsCannotCrossApplications() throws Exception {
        for (String app : List.of("oa", "pmis")) {
            for (String suffix : List.of("-admin", "-user-1")) {
                client();
                login(app + suffix);
                assertThat(get("/api/v1/" + app + "/demo").statusCode()).isEqualTo(200);
                String other = app.equals("oa") ? "pmis" : "oa";
                assertThat(get("/api/v1/" + other + "/demo").statusCode()).isEqualTo(403);
                assertThat(get("/api/v1/admin/demo").statusCode())
                        .isEqualTo(suffix.equals("-admin") ? 200 : 403);
            }
        }
    }

    @Test
    void migrationConstraintsAndEnabledFlagsAreReal() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE",
                                Integer.class))
                .isEqualTo(3);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO auth_user_role(user_id,role_id) VALUES(999999,1)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // MySQL CHECK错误3819使用HY000，Spring可能归为未分类SQL异常；核验真实约束错误。
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO auth_user_role(user_id,role_id,valid_from,valid_to) SELECT id,1,'2026-01-02','2026-01-01' FROM id_user WHERE username='super-admin'"))
                .isInstanceOfSatisfying(
                        org.springframework.dao.DataAccessException.class,
                        failure ->
                                assertThat(failure.getMostSpecificCause())
                                        .isInstanceOfSatisfying(
                                                SQLException.class,
                                                sql -> {
                                                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                                                    assertThat(sql.getMessage())
                                                            .contains("ck_ur_time");
                                                }));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO auth_role(code,name) VALUES('bootstrap_admin','duplicate')"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void actualJoinHonorsTimeAndDisabledRoleWithoutCache() {
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM id_user WHERE username='super-admin'", Long.class);
        Instant boundary = Instant.parse("2026-10-25T00:00:00Z");
        try {
            jdbc.update(
                    "UPDATE auth_user_role SET valid_from='2026-10-25 00:00:00', valid_to='2026-10-25 00:00:01' WHERE user_id=?",
                    id);
            assertThat(resolver.resolve(id, boundary.minusMillis(1))).isEmpty();
            assertThat(resolver.resolve(id, boundary)).contains("oa:document:view");
            assertThat(resolver.resolve(id, boundary.plusSeconds(1))).isEmpty();
            jdbc.update("UPDATE auth_role SET enabled=FALSE WHERE id=1");
            assertThat(resolver.resolve(id, boundary)).isEmpty();
        } finally {
            jdbc.update("UPDATE auth_role SET enabled=TRUE WHERE id=1");
            jdbc.update(
                    "UPDATE auth_user_role SET valid_from=NULL,valid_to=NULL WHERE user_id=?", id);
        }
    }
}

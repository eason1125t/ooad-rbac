/** 功能：隔离MySQL/Redis中通过真实HTTP验证身份切片，含原子回滚、迁移、交接独立性和并发保护。 */
package com.municipal.rbac.bootstrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.*;
import com.municipal.rbac.audit.AuditLog;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
class IdentityAdministrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4.7"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.6-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.bootstrap.username", () -> "");
        r.add("app.bootstrap.password", () -> "");
        r.add("spring.session.timeout", () -> "30m");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean AuditLog.Mapper audit;
    private Client admin;

    private record Response(int status, JsonNode body, String raw) {
        JsonNode data() {
            return body.path("data");
        }
    }

    private final class Client {
        final HttpClient http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        String token;

        Response request(String method, String path, Object body, boolean csrf) throws Exception {
            var b =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(20));
            if (!method.equals("GET") && csrf) b.header("X-CSRF-TOKEN", token);
            String encoded = body == null ? "" : json.writeValueAsString(body);
            b.header("Content-Type", "application/json")
                    .method(
                            method,
                            method.equals("GET")
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(encoded));
            var response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(), json.readTree(response.body()), response.body());
        }

        Response get(String path) throws Exception {
            return request("GET", path, null, false);
        }

        Response post(String path, Object body) throws Exception {
            return request("POST", path, body, true);
        }

        void csrf() throws Exception {
            token = get("/api/v1/auth/csrf").data().path("token").asText();
        }

        Response login(String username, String password) throws Exception {
            csrf();
            var result =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/api/v1/auth/login"))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .header("X-CSRF-TOKEN", token)
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "username="
                                                            + URLEncoder.encode(
                                                                    username,
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            + "&password="
                                                            + URLEncoder.encode(
                                                                    password,
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() == 200) csrf();
            return new Response(result.statusCode(), json.readTree(result.body()), result.body());
        }
    }

    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    private JsonNode success(Response r, int status) {
        assertThat(r.status()).as(r.raw()).isEqualTo(status);
        return r.data();
    }

    private void error(Response r, int status, String code) {
        assertThat(r.status()).as(r.raw()).isEqualTo(status);
        assertThat(r.body().path("code").asText()).isEqualTo(code);
        assertThat(r.body().path("traceId").asText()).isNotBlank();
    }

    @BeforeEach
    void reset() throws Exception {
        // 仅此类自建容器的合成数据，按外键/树深度清理；绝不连接宿主库。
        db.update("DELETE FROM audit_event");
        db.update("DELETE FROM id_move_preview");
        db.update("UPDATE id_organization SET manager_employee_id=NULL");
        db.update(
                "DELETE FROM auth_user_role WHERE user_id IN(SELECT id FROM id_user WHERE employee_id IS NOT NULL OR username='employee-1')");
        db.update("DELETE FROM id_user WHERE employee_id IS NOT NULL");
        db.update("DELETE FROM id_employee");
        List<Long> nodes =
                db.queryForList(
                        "SELECT o.id FROM id_organization o JOIN id_organization_path p ON p.descendant_id=o.id GROUP BY o.id ORDER BY MAX(p.depth) DESC",
                        Long.class);
        db.update("DELETE FROM id_organization_path");
        for (long id : nodes) db.update("DELETE FROM id_organization WHERE id=?", id);
        db.update("UPDATE id_directory_guard SET version=0");
        db.update(
                "UPDATE id_user SET enabled=TRUE,force_password_change=FALSE,auth_version=auth_version+1,version=0");
        db.update(
                "INSERT IGNORE INTO auth_user_role(user_id,role_id) SELECT u.id,r.id FROM id_user u JOIN auth_role r ON r.code='demo_oa' WHERE u.username='oa-admin'");
        redis.execute(
                (RedisCallback<String>)
                        c -> {
                            c.serverCommands().flushDb();
                            return "OK";
                        });
        admin = new Client();
        assertThat(admin.login("super-admin", "1234").status()).isEqualTo(200);
    }

    private JsonNode org(String code, String type, String parent) throws Exception {
        return success(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj("code", code, "name", code, "nodeType", type, "parentId", parent)),
                201);
    }

    private JsonNode root() throws Exception {
        return org("head-office", "HEADQUARTERS", null);
    }

    private JsonNode employee(String org, String code) throws Exception {
        return success(
                admin.post(
                        "/api/v1/admin/employees",
                        obj(
                                "employeeCode",
                                code,
                                "displayName",
                                "Synthetic Person",
                                "organizationId",
                                org,
                                "positionName",
                                null,
                                "phone",
                                "555-0100")),
                201);
    }

    private JsonNode account(String employee, String username) throws Exception {
        return success(
                admin.post(
                        "/api/v1/admin/accounts",
                        obj("username", username, "employeeId", employee)),
                201);
    }

    @Test
    void migrationUpgradeBackfillsExistingV1AndKeepsChecksum() {
        try (var old = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.7"))) {
            old.start();
            var source =
                    new DriverManagerDataSource(
                            old.getJdbcUrl(), old.getUsername(), old.getPassword());
            Flyway.configure().dataSource(source).target("1").load().migrate();
            var legacy = new JdbcTemplate(source);
            int checksum =
                    legacy.queryForObject(
                            "SELECT checksum FROM flyway_schema_history WHERE version='1'",
                            Integer.class);
            legacy.update(
                    "INSERT INTO id_organization(id,code,name,node_type) VALUES(100,'legacy-root','Legacy Root','HEADQUARTERS')");
            legacy.update(
                    "INSERT INTO id_organization(id,code,name,node_type,parent_id) VALUES(101,'legacy-dept','Legacy Dept','DEPARTMENT',100)");
            var flyway = Flyway.configure().dataSource(source).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(
                            legacy.queryForObject(
                                    "SELECT checksum FROM flyway_schema_history WHERE version='1'",
                                    Integer.class))
                    .isEqualTo(checksum);
            assertThat(
                            legacy.queryForObject(
                                    "SELECT COUNT(*) FROM id_organization_path", Integer.class))
                    .isEqualTo(3);
            assertThatThrownBy(
                            () ->
                                    legacy.update(
                                            "INSERT INTO id_organization(code,name,name_key,node_type) VALUES('other-root','Other','other','HEADQUARTERS')"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }
    }

    @Test
    void organizationHierarchyUniquenessDepthAndSafeStatus() throws Exception {
        String root = root().path("id").asText();
        String branch = org("branch-1", "BRANCH", root).path("id").asText();
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "head-2",
                                "name",
                                "Other",
                                "nodeType",
                                "HEADQUARTERS",
                                "parentId",
                                null)),
                409,
                "ROOT_EXISTS");
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "branch-2",
                                "name",
                                "branch-1",
                                "nodeType",
                                "BRANCH",
                                "parentId",
                                root)),
                409,
                "ORGANIZATION_NAME_CONFLICT");
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "branch-3",
                                "name",
                                "Branch",
                                "nodeType",
                                "BRANCH",
                                "parentId",
                                branch)),
                422,
                "INVALID_HIERARCHY");
        String last = branch;
        for (int i = 0; i < 6; i++)
            last = org("depth-" + i, "DEPARTMENT", last).path("id").asText();
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "depth-7",
                                "name",
                                "Too deep",
                                "nodeType",
                                "DEPARTMENT",
                                "parentId",
                                last)),
                422,
                "ORGANIZATION_DEPTH");
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + root + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                409,
                "ORGANIZATION_IN_USE");
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + branch + "/move-preview",
                        obj("parentId", last, "expectedVersion", 0)),
                422,
                "ORGANIZATION_CYCLE");
        assertThat(success(admin.get("/api/v1/admin/organizations"), 200).path("items").size())
                .isEqualTo(8);
    }

    @Test
    void organizationUpdateVersionManagerAndDisableRules() throws Exception {
        String r = root().path("id").asText();
        String d = org("department-1", "DEPARTMENT", r).path("id").asText();
        String e = employee(d, "emp-2").path("id").asText();
        var updated =
                success(
                        admin.request(
                                "PUT",
                                "/api/v1/admin/organizations/" + d,
                                obj(
                                        "name",
                                        "Updated Department",
                                        "managerEmployeeId",
                                        e,
                                        "expectedVersion",
                                        0),
                                true),
                        200);
        assertThat(updated.path("managerEmployeeId").asText()).isEqualTo(e);
        error(
                admin.request(
                        "PUT",
                        "/api/v1/admin/organizations/" + d,
                        obj("name", "Stale", "managerEmployeeId", null, "expectedVersion", 0),
                        true),
                409,
                "VERSION_CONFLICT");
        error(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                409,
                "MANAGER_IN_USE");
        success(
                admin.request(
                        "PUT",
                        "/api/v1/admin/organizations/" + d,
                        obj(
                                "name",
                                "Updated Department",
                                "managerEmployeeId",
                                null,
                                "expectedVersion",
                                1),
                        true),
                200);
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        var off =
                success(
                        admin.post(
                                "/api/v1/admin/organizations/" + d + "/status",
                                obj("enabled", false, "expectedVersion", 2)),
                        200);
        assertThat(off.path("enabled").asBoolean()).isFalse();
        error(
                admin.post(
                        "/api/v1/admin/employees",
                        obj(
                                "employeeCode",
                                "emp-3",
                                "displayName",
                                "Synthetic",
                                "organizationId",
                                d,
                                "positionName",
                                null,
                                "phone",
                                null)),
                422,
                "ORGANIZATION_DISABLED");
        success(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/status",
                        obj("enabled", true, "expectedVersion", 3)),
                200);
    }

    @Test
    void movePreviewRebuildsClosureAndPreventsReplayStaleAndOtherActor() throws Exception {
        String r = root().path("id").asText();
        String a = org("branch-1", "BRANCH", r).path("id").asText();
        String b = org("branch-2", "BRANCH", r).path("id").asText();
        String d = org("department-1", "DEPARTMENT", a).path("id").asText();
        var preview =
                success(
                        admin.post(
                                "/api/v1/admin/organizations/" + d + "/move-preview",
                                obj("parentId", b, "expectedVersion", 0)),
                        200);
        var token = obj("previewToken", preview.path("token").asText());
        success(admin.post("/api/v1/admin/organizations/" + d + "/move", token), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM id_organization_path WHERE ancestor_id=? AND descendant_id=?",
                                Integer.class,
                                b,
                                d))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM id_organization_path WHERE ancestor_id=? AND descendant_id=?",
                                Integer.class,
                                a,
                                d))
                .isZero();
        error(
                admin.post("/api/v1/admin/organizations/" + d + "/move", token),
                409,
                "INVALID_PREVIEW");
        var stale =
                success(
                        admin.post(
                                "/api/v1/admin/organizations/" + d + "/move-preview",
                                obj("parentId", a, "expectedVersion", 1)),
                        200);
        org("department-2", "DEPARTMENT", r);
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/move",
                        obj("previewToken", stale.path("token").asText())),
                409,
                "VERSION_CONFLICT");
        var expired =
                success(
                        admin.post(
                                "/api/v1/admin/organizations/" + d + "/move-preview",
                                obj("parentId", a, "expectedVersion", 1)),
                        200);
        db.update(
                "UPDATE id_move_preview SET expires_at='2000-01-01' WHERE token=?",
                expired.path("token").asText());
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/move",
                        obj("previewToken", expired.path("token").asText())),
                410,
                "PREVIEW_EXPIRED");
        var foreign =
                success(
                        admin.post(
                                "/api/v1/admin/organizations/" + d + "/move-preview",
                                obj("parentId", a, "expectedVersion", 1)),
                        200);
        db.update(
                "UPDATE id_move_preview SET actor_user_id=(SELECT id FROM id_user WHERE username='oa-admin') WHERE token=?",
                foreign.path("token").asText());
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/move",
                        obj("previewToken", foreign.path("token").asText())),
                409,
                "INVALID_PREVIEW");
    }

    @Test
    void accountCreationIsExplicitUniqueAndDoesNotGrantPermissions() throws Exception {
        String e = employee(root().path("id").asText(), "emp-2").path("id").asText();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM id_user WHERE employee_id=?",
                                Integer.class,
                                e))
                .isZero();
        var created = account(e, "employee-2");
        assertThat(created.path("temporaryPassword").asText()).isEqualTo("1234");
        String u = created.path("account").path("id").asText();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                u))
                .isZero();
        error(
                admin.post(
                        "/api/v1/admin/accounts", obj("username", "employee-3", "employeeId", e)),
                409,
                "DATA_CONFLICT");
        var list = success(admin.get("/api/v1/admin/accounts?q=employee-2&sort=name"), 200);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.toString())
                .doesNotContain("passwordHash", "password_hash", "temporaryPassword");
        Client employee = new Client();
        assertThat(employee.login("employee-2", "1234").status()).isEqualTo(200);
        assertThat(employee.get("/api/v1/oa/demo").status()).isEqualTo(403);
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + rootId() + "/move-preview",
                        obj("parentId", rootId(), "expectedVersion", 0)),
                422,
                "ROOT_MOVE_FORBIDDEN");
    }

    private String rootId() {
        return db.queryForObject(
                "SELECT id FROM id_organization WHERE parent_id IS NULL", String.class);
    }

    @Test
    void employeeFilteringContactsAndUnknownFieldsDoNotBecomeJobTransfer() throws Exception {
        String r = root().path("id").asText();
        String d = org("department-1", "DEPARTMENT", r).path("id").asText();
        String e = employee(d, "emp-2").path("id").asText();
        assertThat(
                        success(admin.get("/api/v1/admin/employees?organizationId=" + r), 200)
                                .path("total")
                                .asInt())
                .isZero();
        assertThat(
                        success(
                                        admin.get(
                                                "/api/v1/admin/employees?organizationId="
                                                        + r
                                                        + "&includeDescendants=true&sort=name"),
                                        200)
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        assertThat(success(admin.get("/api/v1/admin/employees?q=%25"), 200).path("total").asInt())
                .isZero();
        var updated =
                success(
                        admin.request(
                                "PUT",
                                "/api/v1/admin/employees/" + e,
                                obj(
                                        "displayName",
                                        "Updated Synthetic",
                                        "phone",
                                        null,
                                        "expectedVersion",
                                        0),
                                true),
                        200);
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        error(
                admin.request(
                        "PUT",
                        "/api/v1/admin/employees/" + e,
                        obj("displayName", "Updated", "phone", null, "expectedVersion", 0),
                        true),
                409,
                "VERSION_CONFLICT");
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/v1/admin/employees/" + e,
                                        obj(
                                                "displayName",
                                                "Updated",
                                                "phone",
                                                null,
                                                "organizationId",
                                                r,
                                                "expectedVersion",
                                                1),
                                        true)
                                .status())
                .isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/employees?size=0").status()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/employees?page=invalid").status()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/accounts?sort=invalid").status()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/accounts/999999").status()).isEqualTo(404);
    }

    @Test
    void departureRevokesOnlyTargetIdentityAndOwnMemberships() throws Exception {
        String r = root().path("id").asText();
        String e = employee(r, "emp-2").path("id").asText();
        String u = account(e, "employee-2").path("account").path("id").asText();
        db.update(
                "INSERT INTO auth_user_role(user_id,role_id) SELECT ?,id FROM auth_role WHERE code='demo_oa'",
                u);
        Client target = new Client();
        assertThat(target.login("employee-2", "1234").status()).isEqualTo(200);
        Client other = new Client();
        assertThat(other.login("oa-user-1", "1234").status()).isEqualTo(200);
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        assertThat(target.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(other.get("/api/v1/oa/demo").status()).isEqualTo(200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                u))
                .isZero();
        JsonNode before =
                json.readTree(
                        db.queryForObject(
                                "SELECT before_json FROM audit_event WHERE action='EMPLOYEE_STATUS' AND target_id=? ORDER BY id DESC LIMIT 1",
                                String.class,
                                e));
        assertThat(before.path("accountId").asText()).isEqualTo(u);
        assertThat(before.path("memberships").size()).isEqualTo(1);
        assertThat(before.path("memberships").get(0).path("roleId").asText()).isNotBlank();
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                200);
        assertThat(admin.get("/api/v1/admin/accounts/" + u).data().path("enabled").asBoolean())
                .isFalse();
    }

    @Test
    void administratorLosingRoleDoesNotInvalidatePreviouslyAuthorizedOtherUser() throws Exception {
        Client former = new Client();
        assertThat(former.login("oa-admin", "1234").status()).isEqualTo(200);
        Client other = new Client();
        assertThat(other.login("oa-user-1", "1234").status()).isEqualTo(200);
        db.update(
                "DELETE FROM auth_user_role WHERE user_id=(SELECT id FROM id_user WHERE username='oa-admin')");
        assertThat(former.get("/api/v1/oa/demo").status()).isEqualTo(403);
        assertThat(other.get("/api/v1/oa/demo").status()).isEqualTo(200);
        long formerId =
                db.queryForObject("SELECT id FROM id_user WHERE username='oa-admin'", Long.class);
        success(
                admin.post(
                        "/api/v1/admin/accounts/" + formerId + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        assertThat(other.get("/api/v1/oa/demo").status()).isEqualTo(200);
    }

    @Test
    void cannotMoveSubtreeWhoseAuthorizationImpactIsNotYetImplemented() throws Exception {
        String r = root().path("id").asText();
        String a = org("department-1", "DEPARTMENT", r).path("id").asText();
        String b = org("department-2", "DEPARTMENT", r).path("id").asText();
        String e = employee(a, "emp-2").path("id").asText();
        String u = account(e, "employee-2").path("account").path("id").asText();
        db.update(
                "INSERT INTO auth_user_role(user_id,role_id) SELECT ?,id FROM auth_role WHERE code='demo_oa'",
                u);
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + a + "/move-preview",
                        obj("parentId", b, "expectedVersion", 0)),
                409,
                "DEPENDENT_AUTHORIZATION_NOT_READY");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                u))
                .isEqualTo(1);
    }

    @Test
    void lastPlatformAdminAndConcurrentCrossDisableKeepOneAdministrator() throws Exception {
        long first =
                db.queryForObject(
                        "SELECT id FROM id_user WHERE username='super-admin'", Long.class);
        error(
                admin.post(
                        "/api/v1/admin/accounts/" + first + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                409,
                "LAST_PLATFORM_ADMIN");
        String e = employee(root().path("id").asText(), "emp-2").path("id").asText();
        String second = account(e, "employee-2").path("account").path("id").asText();
        db.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,1)", second);
        Client secondAdmin = new Client();
        assertThat(secondAdmin.login("employee-2", "1234").status()).isEqualTo(200);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return admin.post(
                                                "/api/v1/admin/accounts/" + second + "/status",
                                                obj("enabled", false, "expectedVersion", 0))
                                        .status();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return secondAdmin
                                        .post(
                                                "/api/v1/admin/accounts/" + first + "/status",
                                                obj("enabled", false, "expectedVersion", 0))
                                        .status();
                            });
            start.countDown();
            List<Integer> results =
                    List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(s -> s == 200).count()).isEqualTo(1);
            assertThat(results).allMatch(s -> Set.of(200, 401, 403, 409).contains(s));
        }
    }

    @Test
    void passwordResetRevokesSessionAndForcedChangeOnlyAllowsPasswordEndpoints() throws Exception {
        String e = employee(root().path("id").asText(), "emp-2").path("id").asText();
        String u = account(e, "employee-2").path("account").path("id").asText();
        Client target = new Client();
        assertThat(target.login("employee-2", "1234").status()).isEqualTo(200);
        success(
                admin.post(
                        "/api/v1/admin/accounts/" + u + "/password-reset",
                        obj("expectedVersion", 0)),
                200);
        assertThat(target.get("/api/v1/auth/me").status()).isEqualTo(401);
        db.update("UPDATE id_user SET force_password_change=TRUE WHERE id=?", u);
        target = new Client();
        assertThat(target.login("employee-2", "1234").status()).isEqualTo(200);
        assertThat(target.get("/api/v1/auth/me").data().path("forcePasswordChange").asBoolean())
                .isTrue();
        error(target.get("/api/v1/oa/demo"), 403, "PASSWORD_CHANGE_REQUIRED");
        error(
                target.post(
                        "/api/v1/auth/password",
                        obj(
                                "currentPassword",
                                "incorrect",
                                "newPassword",
                                "Synthetic-Password-42")),
                422,
                "CURRENT_PASSWORD_INVALID");
        success(
                target.post(
                        "/api/v1/auth/password",
                        obj("currentPassword", "1234", "newPassword", "Synthetic-Password-42")),
                200);
        assertThat(target.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(new Client().login("employee-2", "Synthetic-Password-42").status())
                .isEqualTo(200);
        assertThat(success(admin.get("/api/v1/admin/audit"), 200).toString())
                .doesNotContain("Synthetic-Password-42", "password_hash", "555-0100");
    }

    @Test
    void rateLimitsUnknownAndKnownFailuresAndIpBudget() throws Exception {
        Client attacker = new Client();
        for (int i = 0; i < 5; i++)
            assertThat(attacker.login("employee-1", "incorrect").status()).isEqualTo(401);
        error(attacker.login("employee-1", "incorrect"), 429, "LOGIN_RATE_LIMITED");
        for (int i = 0; i < 23; i++)
            assertThat(attacker.login("unknown-" + i, "incorrect").status()).isEqualTo(401);
        error(attacker.login("unknown-final", "incorrect"), 429, "LOGIN_RATE_LIMITED");
    }

    @Test
    void missingCsrfBusinessEntryAndSpecificPermissionWithoutPlatformMarkerAreDenied()
            throws Exception {
        assertThat(new Client().get("/api/v1/admin/employees").status()).isEqualTo(401);
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/v1/admin/organizations",
                                        obj(
                                                "code",
                                                "root",
                                                "name",
                                                "Root",
                                                "nodeType",
                                                "HEADQUARTERS",
                                                "parentId",
                                                null),
                                        false)
                                .status())
                .isEqualTo(403);
        Client business = new Client();
        assertThat(business.login("oa-admin", "1234").status()).isEqualTo(200);
        assertThat(business.get("/api/v1/admin/employees").status()).isEqualTo(403);
        db.update(
                "INSERT IGNORE INTO auth_role(code,name) VALUES('identity_view_only','Synthetic view only')");
        db.update(
                "INSERT IGNORE INTO auth_role_permission(role_id,permission_id) SELECT r.id,p.id FROM auth_role r JOIN auth_permission p ON p.code='admin:employee:view' WHERE r.code='identity_view_only'");
        db.update(
                "INSERT INTO auth_user_role(user_id,role_id) SELECT u.id,r.id FROM id_user u JOIN auth_role r ON r.code='identity_view_only' WHERE u.username='employee-1'");
        Client partial = new Client();
        assertThat(partial.login("employee-1", "1234").status()).isEqualTo(200);
        assertThat(partial.get("/api/v1/admin/employees").status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/admin/not-declared").status()).isEqualTo(403);
    }

    @Test
    void invalidInputsAndDisabledRelationsCannotBeBypassed() throws Exception {
        String r = root().path("id").asText();
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "wrong-type",
                                "name",
                                "Invalid",
                                "nodeType",
                                "UNKNOWN",
                                "parentId",
                                r)),
                422,
                "INVALID_ORGANIZATION_TYPE");
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "missing-parent",
                                "name",
                                "Invalid",
                                "nodeType",
                                "DEPARTMENT",
                                "parentId",
                                null)),
                422,
                "PARENT_REQUIRED");
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "nested-head",
                                "name",
                                "Invalid",
                                "nodeType",
                                "HEADQUARTERS",
                                "parentId",
                                r)),
                422,
                "INVALID_HIERARCHY");
        error(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "head-office",
                                "name",
                                "Unique name",
                                "nodeType",
                                "DEPARTMENT",
                                "parentId",
                                r)),
                409,
                "DATA_CONFLICT");
        assertThat(admin.get("/api/v1/admin/organizations/0").status()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/organizations/999999").status()).isEqualTo(404);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/v1/admin/organizations/" + r,
                                        obj(
                                                "name",
                                                "Missing explicit manager",
                                                "expectedVersion",
                                                0),
                                        true)
                                .status())
                .isEqualTo(400);
        String d = org("department-1", "DEPARTMENT", r).path("id").asText();
        String e = employee(d, "emp-2").path("id").asText();
        String u = account(e, "employee-2").path("account").path("id").asText();
        assertThat(
                        success(admin.get("/api/v1/admin/employees/" + e), 200)
                                .path("employeeCode")
                                .asText())
                .isEqualTo("emp-2");
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/move-preview",
                        obj("parentId", r, "expectedVersion", 0)),
                422,
                "MOVE_UNCHANGED");
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", false, "expectedVersion", 1)),
                200);
        error(
                admin.post(
                        "/api/v1/admin/accounts", obj("username", "employee-3", "employeeId", e)),
                422,
                "EMPLOYEE_DISABLED");
        error(
                admin.request(
                        "PUT",
                        "/api/v1/admin/organizations/" + d,
                        obj("name", "Dept", "managerEmployeeId", e, "expectedVersion", 0),
                        true),
                422,
                "EMPLOYEE_DISABLED");
        error(
                admin.post(
                        "/api/v1/admin/accounts/" + u + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                422,
                "EMPLOYEE_DISABLED");
        success(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        success(
                admin.post(
                        "/api/v1/admin/organizations/" + r + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                422,
                "ORGANIZATION_DISABLED");
        error(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                422,
                "ORGANIZATION_DISABLED");
        success(
                admin.post(
                        "/api/v1/admin/organizations/" + r + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                200);
        success(
                admin.post(
                        "/api/v1/admin/organizations/" + d + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                200);
        success(
                admin.post(
                        "/api/v1/admin/employees/" + e + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                200);
        success(
                admin.post(
                        "/api/v1/admin/accounts/" + u + "/status",
                        obj("enabled", true, "expectedVersion", 1)),
                200);
        assertThat(new Client().login("employee-2", "1234").status()).isEqualTo(200);
    }

    @Test
    void untrustworthyRateLimitStateIsDependencyFailure() throws Exception {
        Client attacker = new Client();
        assertThat(attacker.login("unknown-login", "incorrect").status()).isEqualTo(401);
        Set<String> keys = redis.keys("municipal:login:account:*");
        assertThat(keys).hasSize(1);
        redis.opsForValue().set(keys.iterator().next(), "invalid-counter");
        error(attacker.login("unknown-login", "incorrect"), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void auditFailureRollsBackBusinessWriteAndDoesNotLeakDetails() throws Exception {
        doThrow(new DataAccessResourceFailureException("synthetic audit unavailable"))
                .when(audit)
                .append(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        nullable(String.class),
                        nullable(String.class));
        var response =
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                "rollback-root",
                                "name",
                                "Rollback",
                                "nodeType",
                                "HEADQUARTERS",
                                "parentId",
                                null));
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.raw()).doesNotContain("synthetic audit unavailable", "INSERT INTO");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id_organization", Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id_organization_path", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT version FROM id_directory_guard WHERE id=1", Integer.class))
                .isZero();
    }
}

/** 功能：隔离MySQL/Redis中通过真实HTTP验证应用委派、增量上限、人员责任与原子换角。 */
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
class ApplicationAuthorizationIntegrationTest {
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

    @Autowired DemoAccountsInitializer demo;
    String oaAdmin, oaUser, employee, demoRole, permission;
    Client business;
    String base = "/api/v1/admin/applications/2";

    @BeforeEach
    void setup() throws Exception {
        // 仅清理本类新建容器内合成关系；遵循RESTRICT外键顺序，不连接本机库。
        for (String table :
                List.of(
                        "auth_change_preview",
                        "audit_event",
                        "id_move_preview",
                        "auth_person_responsibility",
                        "auth_person_version",
                        "auth_delegation_permission_organization",
                        "auth_delegation_permission",
                        "auth_delegation_role",
                        "auth_delegation_action",
                        "auth_delegation_organization",
                        "auth_delegation",
                        "auth_role_permission_organization",
                        "auth_role_organization",
                        "auth_user_role")) db.update("DELETE FROM " + table);
        db.update("DELETE FROM auth_role_permission WHERE role_id<>1");
        db.update("DELETE FROM auth_role WHERE id<>1");
        db.update("UPDATE id_organization SET manager_employee_id=NULL");
        db.update("DELETE FROM id_user WHERE employee_id IS NOT NULL");
        db.update("DELETE FROM id_employee");
        var orgs =
                db.queryForList(
                        "SELECT o.id FROM id_organization o JOIN id_organization_path p ON p.descendant_id=o.id GROUP BY o.id ORDER BY MAX(p.depth) DESC",
                        Long.class);
        db.update("DELETE FROM id_organization_path");
        for (long id : orgs) db.update("DELETE FROM id_organization WHERE id=?", id);
        db.update(
                "UPDATE id_user SET enabled=TRUE,force_password_change=FALSE,auth_version=auth_version+1,version=0");
        demo.run(null);
        db.update(
                "INSERT INTO auth_user_role(user_id,role_id) SELECT u.id,r.id FROM id_user u JOIN auth_role r ON (u.username='super-admin' AND r.code='bootstrap_admin') OR (u.username IN('oa-admin','pmis-admin') AND r.code='demo_platform_view') OR (u.username IN('oa-admin','oa-user-1') AND r.code='demo_oa') OR (u.username IN('pmis-admin','pmis-user-1') AND r.code='demo_pmis')");
        redis.execute(
                (RedisCallback<String>)
                        c -> {
                            c.serverCommands().flushDb();
                            return "OK";
                        });
        oaAdmin = uid("oa-admin");
        oaUser = uid("oa-user-1");
        employee = uid("employee-1");
        demoRole = rid("demo_oa");
        permission = pid("oa:document:view");
        admin = new Client();
        success(admin.login("super-admin", "1234"), 200);
        business = new Client();
        success(business.login("oa-admin", "1234"), 200);
    }

    String uid(String name) {
        return db.queryForObject("SELECT id FROM id_user WHERE username=?", String.class, name);
    }

    String rid(String code) {
        return db.queryForObject("SELECT id FROM auth_role WHERE code=?", String.class, code);
    }

    String pid(String code) {
        return db.queryForObject("SELECT id FROM auth_permission WHERE code=?", String.class, code);
    }

    Map<String, Object> all() {
        return obj("kind", "ALL", "organizationIds", List.of());
    }

    Map<String, Object> peopleAll() {
        return obj("allCompany", true, "organizations", List.of());
    }

    JsonNode delegation(String user) throws Exception {
        return success(
                admin.post(
                        base + "/delegations",
                        obj(
                                "administratorUserId",
                                user,
                                "enabled",
                                true,
                                "peopleScope",
                                peopleAll(),
                                "commonRoleIds",
                                List.of(demoRole),
                                "permissionCeilings",
                                List.of(obj("permissionId", permission, "scope", all())),
                                "expectedVersion",
                                null)),
                201);
    }

    JsonNode responsibility(String user, String d) throws Exception {
        return success(
                admin.request(
                        "PUT",
                        base + "/responsibilities/" + user,
                        obj("delegationId", d, "expectedVersion", null),
                        true),
                200);
    }

    JsonNode role(Client client, String kind, String owner) throws Exception {
        return success(
                client.post(
                        base + "/roles",
                        obj(
                                "code",
                                "case-role",
                                "name",
                                "Synthetic role",
                                "kind",
                                kind,
                                "ownerUserId",
                                owner,
                                "personnelScope",
                                peopleAll())),
                201);
    }

    JsonNode permissions(Client client, String role, long version, List<?> list) throws Exception {
        var p =
                success(
                        client.post(
                                base + "/roles/" + role + "/permissions/preview",
                                obj("expectedVersion", version, "permissions", list)),
                        200);
        return success(
                client.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", p.path("token").asText()),
                        true),
                200);
    }

    JsonNode grant(Client client, String user, long version, List<?> list) throws Exception {
        var p =
                success(
                        client.post(
                                base + "/users/" + user + "/roles/preview",
                                obj("expectedVersion", version, "memberships", list)),
                        200);
        return success(
                client.request(
                        "PUT",
                        base + "/users/" + user + "/roles",
                        obj("previewToken", p.path("token").asText()),
                        true),
                200);
    }

    JsonNode scoped() throws Exception {
        delegation(oaAdmin);
        return role(business, "SCOPED", oaAdmin);
    }

    List<?> businessPoint() {
        return List.of(obj("permissionId", permission, "scope", all()));
    }

    List<?> member(String role) {
        return List.of(obj("roleId", role));
    }

    @Test
    void qualificationExplicitlyControlsConsoleAndApplication() throws Exception {
        assertThat(business.get("/api/v1/admin/managed-applications").status()).isEqualTo(403);
        delegation(oaAdmin);
        var apps = success(business.get("/api/v1/admin/managed-applications"), 200).path("items");
        assertThat(apps.size()).isEqualTo(1);
        assertThat(apps.get(0).path("id").asText()).isEqualTo("2");
        assertThat(business.get("/api/v1/admin/applications/3/roles").status()).isEqualTo(403);
        assertThat(business.get("/api/v1/admin/accounts").status()).isEqualTo(403);
        success(business.get(base + "/permissions"), 200);
    }

    @Test
    void rolePermissionAndUserGrantHaveRealEffectiveSources() throws Exception {
        var r = scoped();
        String role = r.path("id").asText();
        permissions(business, role, 0, businessPoint());
        var d = success(admin.get(base + "/delegations"), 200).path("items").get(0);
        responsibility(employee, d.path("id").asText());
        grant(business, employee, 0, member(role));
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
        var effective =
                success(target.get("/api/v1/auth/effective-permissions?applicationId=2"), 200);
        assertThat(
                        effective
                                .path("permissions")
                                .get(0)
                                .path("capabilities")
                                .path("all")
                                .asBoolean())
                .isTrue();
        assertThat(effective.path("sources").get(0).path("grantedByUserId").asText())
                .isEqualTo(oaAdmin);
        success(business.get(base + "/roles/" + role + "/members"), 200);
        success(business.get(base + "/people"), 200);
    }

    @Test
    void revokedAdministratorBusinessPermissionsDoNotClipEmployees() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        grant(business, employee, 0, member(demoRole));
        db.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", oaAdmin, demoRole);
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
        responsibility(oaUser, d.path("id").asText());
        var denial =
                business.post(
                        base + "/users/" + oaUser + "/roles/preview",
                        obj(
                                "expectedVersion",
                                0,
                                "memberships",
                                List.of(
                                        obj(
                                                "roleId",
                                                demoRole,
                                                "validFrom",
                                                null,
                                                "validTo",
                                                null))));
        // Existing unchanged role may be retained even after the administrator loses its
        // permission.
        success(denial, 200);
        grant(business, employee, 1, List.of()); // pure revoke remains allowed
        assertThat(target.get("/api/v1/oa/demo").status()).isEqualTo(403);
    }

    @Test
    void missingPersonalPermissionPreventsNewGrantButNotRevoke() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        db.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", oaAdmin, demoRole);
        error(
                business.post(
                        base + "/users/" + employee + "/roles/preview",
                        obj("expectedVersion", 0, "memberships", member(demoRole))),
                403,
                "CURRENT_PERMISSION_CEILING_EXCEEDED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                employee))
                .isZero();
    }

    @Test
    void noBusinessAdministratorCanManageSelfPeerOrCommonDefinition() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(oaAdmin, d.path("id").asText());
        error(
                business.post(
                        base + "/users/" + oaAdmin + "/roles/preview",
                        obj("expectedVersion", 0, "memberships", List.of())),
                403,
                "ADMINISTRATOR_TARGET_DENIED");
        assertThat(
                        business.request(
                                        "PUT",
                                        base + "/roles/" + demoRole,
                                        obj(
                                                "name",
                                                "Changed",
                                                "personnelScope",
                                                peopleAll(),
                                                "expectedVersion",
                                                0),
                                        true)
                                .status())
                .isEqualTo(403);
        var other = delegation(uid("pmis-admin"));
        responsibility(uid("pmis-admin"), d.path("id").asText());
        assertThat(
                        business.post(
                                        base + "/users/" + uid("pmis-admin") + "/roles/preview",
                                        obj("expectedVersion", 0, "memberships", List.of()))
                                .status())
                .isEqualTo(403);
    }

    @Test
    void delegationDisableMarksAwaitingWithoutChangingEmployeeMembership() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        grant(business, employee, 0, member(demoRole));
        String before =
                db.queryForObject(
                        "SELECT CONCAT(role_id,':',granted_by,':',granted_at) FROM auth_user_role WHERE user_id=?",
                        String.class,
                        employee);
        success(
                admin.post(
                        base + "/delegations/" + d.path("id").asText() + "/status",
                        obj("enabled", false, "expectedVersion", 0)),
                200);
        assertThat(business.get(base + "/roles").status()).isEqualTo(403);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM auth_person_responsibility WHERE application_id=2 AND user_id=?",
                                String.class,
                                employee))
                .isEqualTo("AWAITING");
        assertThat(
                        db.queryForObject(
                                "SELECT CONCAT(role_id,':',granted_by,':',granted_at) FROM auth_user_role WHERE user_id=?",
                                String.class,
                                employee))
                .isEqualTo(before);
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void previewsBindActorVersionNamespaceAndAreSingleUse() throws Exception {
        var r = scoped();
        String role = r.path("id").asText();
        var preview =
                success(
                        business.post(
                                base + "/roles/" + role + "/permissions/preview",
                                obj("expectedVersion", 0, "permissions", businessPoint())),
                        200);
        String token = preview.path("token").asText();
        error(
                admin.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", token),
                        true),
                409,
                "INVALID_PREVIEW");
        success(
                business.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", token),
                        true),
                200);
        error(
                business.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", token),
                        true),
                409,
                "INVALID_PREVIEW");
        var stale =
                success(
                        business.post(
                                base + "/roles/" + role + "/permissions/preview",
                                obj("expectedVersion", 1, "permissions", List.of())),
                        200);
        success(
                admin.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", false, "expectedVersion", 1)),
                200);
        error(
                business.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", stale.path("token").asText()),
                        true),
                409,
                "STALE_AUTHORIZATION_PREVIEW");
    }

    @Test
    void expiredPreviewAndCsrfFailWithoutMutation() throws Exception {
        String role = scoped().path("id").asText();
        var p =
                success(
                        business.post(
                                base + "/roles/" + role + "/permissions/preview",
                                obj("expectedVersion", 0, "permissions", businessPoint())),
                        200);
        db.update(
                "UPDATE auth_change_preview SET expires_at='2000-01-01' WHERE token=?",
                p.path("token").asText());
        error(
                business.request(
                        "PUT",
                        base + "/roles/" + role + "/permissions",
                        obj("previewToken", p.path("token").asText()),
                        true),
                410,
                "PREVIEW_EXPIRED");
        assertThat(business.request("POST", base + "/roles", obj(), false).status()).isEqualTo(403);
    }

    @Test
    void crossApplicationPermissionAndPlannedPermissionAreRejected() throws Exception {
        String role = scoped().path("id").asText();
        error(
                business.post(
                        base + "/roles/" + role + "/permissions/preview",
                        obj(
                                "expectedVersion",
                                0,
                                "permissions",
                                List.of(
                                        obj(
                                                "permissionId",
                                                pid("pmis:project:view"),
                                                "scope",
                                                all())))),
                422,
                "CROSS_APPLICATION_PERMISSION");
        db.update(
                "UPDATE auth_permission SET implementation_status='PLANNED' WHERE id=?",
                permission);
        try {
            error(
                    admin.post(
                            base + "/roles/" + role + "/permissions/preview",
                            obj("expectedVersion", 0, "permissions", businessPoint())),
                    422,
                    "PERMISSION_NOT_IMPLEMENTED");
        } finally {
            db.update(
                    "UPDATE auth_permission SET implementation_status='IMPLEMENTED' WHERE id=?",
                    permission);
        }
    }

    @Test
    void memberBatchRollsBackAllOnOneScopeFailureAndRemovalIsIdempotent() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        assertThat(
                        business.post(
                                        base + "/roles/" + demoRole + "/members",
                                        obj(
                                                "expectedVersion",
                                                0,
                                                "members",
                                                List.of(
                                                        obj(
                                                                "userId",
                                                                employee,
                                                                "expectedVersion",
                                                                0),
                                                        obj(
                                                                "userId",
                                                                oaUser,
                                                                "expectedVersion",
                                                                0))))
                                .status())
                .isEqualTo(403);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                employee))
                .isZero();
        success(
                business.post(
                        base + "/roles/" + demoRole + "/members",
                        obj(
                                "expectedVersion",
                                0,
                                "members",
                                List.of(obj("userId", employee, "expectedVersion", 0)))),
                200);
        success(
                business.post(
                        base + "/roles/" + demoRole + "/members/remove",
                        obj("userId", employee, "expectedVersion", 1, "expectedUserVersion", 1)),
                200);
        success(
                business.post(
                        base + "/roles/" + demoRole + "/members/remove",
                        obj("userId", employee, "expectedVersion", 0, "expectedUserVersion", 0)),
                200);
    }

    @Test
    void auditFailureRollsBackGrantAndPreviewConsumption() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        var p =
                success(
                        business.post(
                                base + "/users/" + employee + "/roles/preview",
                                obj("expectedVersion", 0, "memberships", member(demoRole))),
                        200);
        doThrow(new DataAccessResourceFailureException("simulated audit failure"))
                .when(audit)
                .append(
                        anyLong(),
                        anyString(),
                        eq("USER_ROLES"),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString());
        try {
            assertThat(
                            business.request(
                                            "PUT",
                                            base + "/users/" + employee + "/roles",
                                            obj("previewToken", p.path("token").asText()),
                                            true)
                                    .status())
                    .isEqualTo(503);
        } finally {
            org.mockito.Mockito.reset(audit);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                employee))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT consumed FROM auth_change_preview WHERE token=?",
                                Boolean.class,
                                p.path("token").asText()))
                .isFalse();
        success(
                business.request(
                        "PUT",
                        base + "/users/" + employee + "/roles",
                        obj("previewToken", p.path("token").asText()),
                        true),
                200);
    }

    @Test
    void roleOwnerHandoverDoesNotRequireNewOwnersPersonalCeiling() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        String role = role(business, "SCOPED", oaAdmin).path("id").asText();
        permissions(business, role, 0, businessPoint());
        grant(business, employee, 0, member(role));
        var next = delegation(uid("pmis-admin"));
        String original =
                db.queryForObject(
                        "SELECT CONCAT(granted_by,':',granted_at) FROM auth_user_role WHERE user_id=? AND role_id=?",
                        String.class,
                        employee,
                        role);
        var p =
                success(
                        admin.post(
                                base + "/roles/" + role + "/owner-preview",
                                obj(
                                        "expectedVersion",
                                        1,
                                        "newAdministratorUserId",
                                        uid("pmis-admin"))),
                        200);
        success(
                admin.request(
                        "PUT",
                        base + "/roles/" + role + "/owner",
                        obj("previewToken", p.path("token").asText()),
                        true),
                200);
        assertThat(
                        db.queryForObject(
                                "SELECT CONCAT(granted_by,':',granted_at) FROM auth_user_role WHERE user_id=? AND role_id=?",
                                String.class,
                                employee,
                                role))
                .isEqualTo(original);
        assertThat(
                        db.queryForObject(
                                "SELECT delegation_id FROM auth_person_responsibility WHERE application_id=2 AND user_id=?",
                                String.class,
                                employee))
                .isEqualTo(next.path("id").asText());
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void administratorAppointmentCannotActivateAnExistingScopedMember() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        String role = role(business, "SCOPED", oaAdmin).path("id").asText();
        permissions(business, role, 0, businessPoint());
        grant(business, employee, 0, member(role));
        assertThat(
                        admin.post(
                                        base + "/delegations",
                                        obj(
                                                "administratorUserId",
                                                employee,
                                                "enabled",
                                                true,
                                                "peopleScope",
                                                peopleAll(),
                                                "commonRoleIds",
                                                List.of(demoRole),
                                                "permissionCeilings",
                                                businessPoint(),
                                                "expectedVersion",
                                                null))
                                .status())
                .isEqualTo(422);
    }

    JsonNode createOrg(String code, String parent) throws Exception {
        return success(
                admin.post(
                        "/api/v1/admin/organizations",
                        obj(
                                "code",
                                code,
                                "name",
                                code,
                                "nodeType",
                                parent == null ? "HEADQUARTERS" : "DEPARTMENT",
                                "parentId",
                                parent)),
                201);
    }

    JsonNode createEmployee(String code, String org) throws Exception {
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
                                null)),
                201);
    }

    JsonNode createAccount(String name, String employee) throws Exception {
        return success(
                        admin.post(
                                "/api/v1/admin/accounts",
                                obj("username", name, "employeeId", employee)),
                        201)
                .path("account");
    }

    Map<String, Object> transfer(String org, String action, List<?> apps) {
        return obj(
                "expectedVersion",
                0,
                "expectedAccountVersion",
                0,
                "organizationId",
                org,
                "positionName",
                "Synthetic Position",
                "managementAction",
                action,
                "applications",
                apps);
    }

    @Test
    void personnelTransferAtomicallyReplacesOwnRolesAndRetiresManagementOnly() throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String department = createOrg("department-a", root).path("id").asText();
        String e = createEmployee("transfer-person", root).path("id").asText();
        String u = createAccount("transfer-admin", e).path("id").asText();
        grant(admin, u, 0, member(demoRole));
        var d = delegation(u);
        responsibility(employee, d.path("id").asText());
        grant(admin, employee, 0, member(demoRole));
        String otherBefore =
                db.queryForObject(
                        "SELECT CONCAT(granted_by,':',granted_at) FROM auth_user_role WHERE user_id=?",
                        String.class,
                        employee);
        var command = transfer(department, "RETIRE", List.of());
        var p =
                success(
                        admin.post("/api/v1/admin/employees/" + e + "/assignment-preview", command),
                        200);
        var result =
                success(
                        admin.request(
                                "PUT",
                                "/api/v1/admin/employees/" + e + "/assignment",
                                obj("previewToken", p.path("token").asText()),
                                true),
                        200);
        assertThat(result.path("employee").path("organizationId").asText()).isEqualTo(department);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Integer.class,
                                u))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT enabled FROM auth_delegation WHERE id=?",
                                Boolean.class,
                                d.path("id").asText()))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM auth_person_responsibility WHERE user_id=? AND application_id=2",
                                String.class,
                                employee))
                .isEqualTo("AWAITING");
        assertThat(
                        db.queryForObject(
                                "SELECT CONCAT(granted_by,':',granted_at) FROM auth_user_role WHERE user_id=?",
                                String.class,
                                employee))
                .isEqualTo(otherBefore);
        Client staff = new Client();
        success(staff.login("employee-1", "1234"), 200);
        success(staff.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void personnelTransferFailureRestoresPositionRolesAndPreview() throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String dept = createOrg("department-a", root).path("id").asText();
        String e = createEmployee("transfer-person", root).path("id").asText();
        String u = createAccount("transfer-user", e).path("id").asText();
        grant(admin, u, 0, member(demoRole));
        var command =
                transfer(
                        dept,
                        "KEEP",
                        List.of(
                                obj(
                                        "applicationId",
                                        "3",
                                        "memberships",
                                        member(rid("demo_pmis")),
                                        "responsibilityDelegationId",
                                        null)));
        var p =
                success(
                        admin.post("/api/v1/admin/employees/" + e + "/assignment-preview", command),
                        200);
        doThrow(new DataAccessResourceFailureException("simulated assignment audit failure"))
                .when(audit)
                .append(
                        anyLong(),
                        anyString(),
                        eq("EMPLOYEE_ASSIGNMENT"),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString());
        try {
            assertThat(
                            admin.request(
                                            "PUT",
                                            "/api/v1/admin/employees/" + e + "/assignment",
                                            obj("previewToken", p.path("token").asText()),
                                            true)
                                    .status())
                    .isEqualTo(503);
        } finally {
            org.mockito.Mockito.reset(audit);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT organization_id FROM id_employee WHERE id=?",
                                String.class,
                                e))
                .isEqualTo(root);
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM auth_user_role WHERE user_id=?",
                                String.class,
                                u))
                .containsExactly(demoRole);
        assertThat(
                        db.queryForObject(
                                "SELECT consumed FROM auth_change_preview WHERE token=?",
                                Boolean.class,
                                p.path("token").asText()))
                .isFalse();
        success(
                admin.request(
                        "PUT",
                        "/api/v1/admin/employees/" + e + "/assignment",
                        obj("previewToken", p.path("token").asText()),
                        true),
                200);
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM auth_user_role WHERE user_id=?",
                                String.class,
                                u))
                .containsExactly(rid("demo_pmis"));
    }

    @Test
    void organizationDelegationFiltersBeforePaginationAndShrinkDoesNotRevoke() throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String dept = createOrg("department-a", root).path("id").asText();
        String other = createOrg("department-b", root).path("id").asText();
        String ea = createEmployee("employee-a", dept).path("id").asText();
        String eb = createEmployee("employee-b", other).path("id").asText();
        String ua = createAccount("staff-a", ea).path("id").asText();
        String ub = createAccount("staff-b", eb).path("id").asText();
        var d = delegation(oaAdmin);
        responsibility(ua, d.path("id").asText());
        responsibility(ub, d.path("id").asText());
        grant(business, ua, 0, member(demoRole));
        grant(business, ub, 0, member(demoRole));
        var scope =
                obj(
                        "allCompany",
                        false,
                        "organizations",
                        List.of(obj("organizationId", dept, "includeDescendants", false)));
        success(
                admin.request(
                        "PUT",
                        base + "/delegations/" + d.path("id").asText(),
                        obj(
                                "administratorUserId",
                                oaAdmin,
                                "enabled",
                                true,
                                "peopleScope",
                                scope,
                                "commonRoleIds",
                                List.of(demoRole),
                                "permissionCeilings",
                                businessPoint(),
                                "expectedVersion",
                                0),
                        true),
                200);
        var list = success(business.get(base + "/people?page=1&size=1"), 200);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("userId").asText()).isEqualTo(ua);
        assertThat(business.get(base + "/users/" + ub + "/roles").status()).isEqualTo(403);
        Client target = new Client();
        success(target.login("staff-b", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void scopeExpansionIsCheckedButNarrowingAfterPersonalLossStillWorks() throws Exception {
        var d = delegation(oaAdmin);
        String role = role(business, "SCOPED", oaAdmin).path("id").asText();
        permissions(
                business,
                role,
                0,
                List.of(
                        obj(
                                "permissionId",
                                permission,
                                "scope",
                                obj("kind", "SELF", "organizationIds", List.of()))));
        success(
                admin.request(
                        "PUT",
                        base + "/delegations/" + d.path("id").asText(),
                        obj(
                                "administratorUserId",
                                oaAdmin,
                                "enabled",
                                true,
                                "peopleScope",
                                peopleAll(),
                                "commonRoleIds",
                                List.of(demoRole),
                                "permissionCeilings",
                                List.of(
                                        obj(
                                                "permissionId",
                                                permission,
                                                "scope",
                                                obj("kind", "SELF", "organizationIds", List.of()))),
                                "expectedVersion",
                                0),
                        true),
                200);
        assertThat(
                        business.post(
                                        base + "/roles/" + role + "/permissions/preview",
                                        obj("expectedVersion", 1, "permissions", businessPoint()))
                                .status())
                .isEqualTo(403);
        db.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", oaAdmin, demoRole);
        permissions(business, role, 1, List.of());
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?",
                                Integer.class,
                                role))
                .isZero();
    }

    @Test
    void membershipExpirationAndFutureStartUseHalfOpenInterval() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        grant(
                business,
                employee,
                0,
                List.of(
                        obj(
                                "roleId",
                                demoRole,
                                "validFrom",
                                "2099-01-01T00:00:00Z",
                                "validTo",
                                null)));
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        assertThat(target.get("/api/v1/oa/demo").status()).isEqualTo(403);
        grant(
                business,
                employee,
                1,
                List.of(
                        obj(
                                "roleId",
                                demoRole,
                                "validFrom",
                                null,
                                "validTo",
                                "2000-01-01T00:00:00Z")));
        assertThat(target.get("/api/v1/oa/demo").status()).isEqualTo(403);
        grant(business, employee, 2, member(demoRole));
        success(target.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void staleConcurrentGrantCommitsOnlyOnePreview() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        var r = obj("expectedVersion", 0, "memberships", member(demoRole));
        var p = success(business.post(base + "/users/" + employee + "/roles/preview", r), 200);
        var q = success(business.post(base + "/users/" + employee + "/roles/preview", r), 200);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    business.request(
                                                    "PUT",
                                                    base + "/users/" + employee + "/roles",
                                                    obj("previewToken", p.path("token").asText()),
                                                    true)
                                            .status());
            var second =
                    pool.submit(
                            () ->
                                    business.request(
                                                    "PUT",
                                                    base + "/users/" + employee + "/roles",
                                                    obj("previewToken", q.path("token").asText()),
                                                    true)
                                            .status());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT version FROM auth_person_version WHERE user_id=? AND application_id=2",
                                Integer.class,
                                employee))
                .isEqualTo(1);
    }

    @Test
    void platformRoleCannotRemoveTheLastAdministratorQualification() throws Exception {
        String app = "/api/v1/admin/applications/1";
        var role =
                success(
                                admin.post(
                                        app + "/roles",
                                        obj(
                                                "code",
                                                "platform-case",
                                                "name",
                                                "Synthetic platform role",
                                                "kind",
                                                "PLATFORM",
                                                "ownerUserId",
                                                null,
                                                "personnelScope",
                                                peopleAll())),
                                201)
                        .path("id")
                        .asText();
        db.update(
                "INSERT INTO auth_role_permission(role_id,permission_id) SELECT ?,p.id FROM auth_permission p WHERE p.application_id=1",
                role);
        db.update(
                "INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)",
                uid("super-admin"),
                role);
        db.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=1", uid("super-admin"));
        var p =
                success(
                        admin.post(
                                app + "/roles/" + role + "/permissions/preview",
                                obj("expectedVersion", 0, "permissions", List.of())),
                        200);
        error(
                admin.request(
                        "PUT",
                        app + "/roles/" + role + "/permissions",
                        obj("previewToken", p.path("token").asText()),
                        true),
                409,
                "LAST_PLATFORM_ADMIN");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?",
                                Integer.class,
                                role))
                .isPositive();
        assertThat(
                        db.queryForObject(
                                "SELECT consumed FROM auth_change_preview WHERE token=?",
                                Boolean.class,
                                p.path("token").asText()))
                .isFalse();
        error(
                admin.post(
                        app + "/roles/" + role + "/status",
                        obj("expectedVersion", 0, "enabled", false)),
                409,
                "LAST_PLATFORM_ADMIN");
    }

    @Test
    void delegationExpirationImmediatelyShowsAwaitingAndPreservesEmployeeAccess() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        grant(business, employee, 0, member(demoRole));
        db.update(
                "UPDATE auth_delegation SET valid_to='2000-01-01' WHERE id=?",
                d.path("id").asText());
        assertThat(business.get(base + "/roles").status()).isEqualTo(403);
        var r = success(admin.get(base + "/responsibilities/" + employee), 200);
        assertThat(r.path("state").asText()).isEqualTo("AWAITING");
        assertThat(r.path("version").asLong()).isZero();
        Client target = new Client();
        success(target.login("employee-1", "1234"), 200);
        success(target.get("/api/v1/oa/demo"), 200);
        success(
                admin.request(
                        "PUT",
                        base + "/delegations/" + d.path("id").asText(),
                        obj(
                                "administratorUserId",
                                oaAdmin,
                                "enabled",
                                true,
                                "peopleScope",
                                peopleAll(),
                                "commonRoleIds",
                                List.of(demoRole),
                                "permissionCeilings",
                                businessPoint(),
                                "expectedVersion",
                                0),
                        true),
                200);
        assertThat(
                        success(admin.get(base + "/responsibilities/" + employee), 200)
                                .path("state")
                                .asText())
                .isEqualTo("AWAITING");
        assertThat(business.get(base + "/users/" + employee + "/roles").status()).isEqualTo(403);
        success(target.get("/api/v1/oa/demo"), 200);
    }

    @Test
    void existingV2UpgradePreservesBothMigrationChecksums() {
        try (var old = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.7"))) {
            old.start();
            var source =
                    new DriverManagerDataSource(
                            old.getJdbcUrl(), old.getUsername(), old.getPassword());
            Flyway.configure().dataSource(source).target("2").load().migrate();
            var sql = new JdbcTemplate(source);
            var checksums =
                    sql.queryForList(
                            "SELECT version,checksum FROM flyway_schema_history WHERE version IN('1','2') ORDER BY version");
            sql.update("INSERT INTO auth_role(code,name) VALUES('demo_oa','Existing OA')");
            Flyway f = Flyway.configure().dataSource(source).load();
            assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
            f.validate();
            assertThat(
                            sql.queryForList(
                                    "SELECT version,checksum FROM flyway_schema_history WHERE version IN('1','2') ORDER BY version"))
                    .isEqualTo(checksums);
            assertThat(
                            sql.queryForObject(
                                    "SELECT application_id FROM auth_role WHERE code='demo_oa'",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                                    Integer.class))
                    .isEqualTo(25);
        }
    }

    @Test
    void multipleScopedRolesCanHandOverWithoutRemovingAnyEmployeeRole() throws Exception {
        var d = delegation(oaAdmin);
        responsibility(employee, d.path("id").asText());
        String first = role(business, "SCOPED", oaAdmin).path("id").asText();
        String second =
                success(
                                business.post(
                                        base + "/roles",
                                        obj(
                                                "code",
                                                "case-role-two",
                                                "name",
                                                "Second synthetic role",
                                                "kind",
                                                "SCOPED",
                                                "ownerUserId",
                                                oaAdmin,
                                                "personnelScope",
                                                peopleAll())),
                                201)
                        .path("id")
                        .asText();
        permissions(business, first, 0, businessPoint());
        permissions(business, second, 0, businessPoint());
        grant(business, employee, 0, List.of(obj("roleId", first), obj("roleId", second)));
        var next = delegation(uid("pmis-admin"));
        var before =
                db.queryForList(
                        "SELECT role_id,granted_by,granted_at FROM auth_user_role WHERE user_id=? ORDER BY role_id",
                        employee);
        Client staff = new Client();
        success(staff.login("employee-1", "1234"), 200);
        for (String role : List.of(first, second)) {
            var p =
                    success(
                            admin.post(
                                    base + "/roles/" + role + "/owner-preview",
                                    obj(
                                            "expectedVersion",
                                            1,
                                            "newAdministratorUserId",
                                            uid("pmis-admin"))),
                            200);
            success(
                    admin.request(
                            "PUT",
                            base + "/roles/" + role + "/owner",
                            obj("previewToken", p.path("token").asText()),
                            true),
                    200);
            assertThat(
                            db.queryForList(
                                    "SELECT role_id,granted_by,granted_at FROM auth_user_role WHERE user_id=? ORDER BY role_id",
                                    employee))
                    .isEqualTo(before);
            success(staff.get("/api/v1/oa/demo"), 200);
            assertThat(
                            success(admin.get(base + "/responsibilities/" + employee), 200)
                                    .path("state")
                                    .asText())
                    .isEqualTo(role.equals(first) ? "AWAITING" : "CURRENT");
        }
        assertThat(
                        success(admin.get(base + "/responsibilities/" + employee), 200)
                                .path("delegationId")
                                .asText())
                .isEqualTo(next.path("id").asText());
    }

    @Test
    void roleLifecycleVersionsAndRecoveryRecheckPersonalCeiling() throws Exception {
        String role = scoped().path("id").asText();
        permissions(business, role, 0, businessPoint());
        var updated =
                success(
                        business.request(
                                "PUT",
                                base + "/roles/" + role,
                                obj(
                                        "name",
                                        "Updated role",
                                        "personnelScope",
                                        peopleAll(),
                                        "expectedVersion",
                                        1),
                                true),
                        200);
        assertThat(updated.path("name").asText()).isEqualTo("Updated role");
        success(business.get(base + "/roles/" + role), 200);
        error(
                business.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", false, "expectedVersion", 1)),
                409,
                "VERSION_CONFLICT");
        success(
                business.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", false, "expectedVersion", 2)),
                200);
        success(
                business.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", false, "expectedVersion", 3)),
                200);
        db.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", oaAdmin, demoRole);
        error(
                business.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", true, "expectedVersion", 3)),
                403,
                "CURRENT_PERMISSION_CEILING_EXCEEDED");
        success(
                admin.post(
                        base + "/roles/" + role + "/status",
                        obj("enabled", true, "expectedVersion", 3)),
                200);
        assertThat(
                        success(admin.get(base + "/roles/" + role + "/permissions"), 200)
                                .path("permissions")
                                .size())
                .isEqualTo(1);
    }

    @Test
    void delegationAndRolePersonnelScopesRejectExpansionOutsideAssignedOrganization()
            throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String dept = createOrg("department-a", root).path("id").asText();
        String other = createOrg("department-b", root).path("id").asText();
        var people =
                obj(
                        "allCompany",
                        false,
                        "organizations",
                        List.of(obj("organizationId", dept, "includeDescendants", false)));
        var d =
                success(
                        admin.post(
                                base + "/delegations",
                                obj(
                                        "administratorUserId",
                                        oaAdmin,
                                        "enabled",
                                        true,
                                        "peopleScope",
                                        people,
                                        "commonRoleIds",
                                        List.of(demoRole),
                                        "permissionCeilings",
                                        businessPoint(),
                                        "expectedVersion",
                                        null)),
                        201);
        assertThat(
                        business.post(
                                        base + "/roles",
                                        obj(
                                                "code",
                                                "outside-role",
                                                "name",
                                                "Outside",
                                                "kind",
                                                "SCOPED",
                                                "ownerUserId",
                                                oaAdmin,
                                                "personnelScope",
                                                peopleAll()))
                                .status())
                .isEqualTo(422);
        String role =
                success(
                                business.post(
                                        base + "/roles",
                                        obj(
                                                "code",
                                                "inside-role",
                                                "name",
                                                "Inside",
                                                "kind",
                                                "SCOPED",
                                                "ownerUserId",
                                                oaAdmin,
                                                "personnelScope",
                                                people)),
                                201)
                        .path("id")
                        .asText();
        assertThat(
                        business.request(
                                        "PUT",
                                        base + "/roles/" + role,
                                        obj(
                                                "name",
                                                "Expanded",
                                                "personnelScope",
                                                peopleAll(),
                                                "expectedVersion",
                                                0),
                                        true)
                                .status())
                .isEqualTo(422);
        String e = createEmployee("outside-person", other).path("id").asText();
        String u = createAccount("outside-user", e).path("id").asText();
        error(
                admin.request(
                        "PUT",
                        base + "/responsibilities/" + u,
                        obj("delegationId", d.path("id").asText(), "expectedVersion", null),
                        true),
                422,
                "RESPONSIBILITY_SCOPE_DENIED");
        assertThat(business.get(base + "/users/" + u + "/roles").status()).isEqualTo(403);
        assertThat(business.get(base + "/people?q=outside-user").data().path("total").asInt())
                .isZero();
        error(
                admin.post(
                        "/api/v1/admin/organizations/" + dept + "/move-preview",
                        obj("parentId", other, "expectedVersion", 0)),
                409,
                "DEPENDENT_AUTHORIZATION_NOT_READY");
    }

    @Test
    void malformedOrCrossApplicationDefinitionsNeverCreatePartialAuthorization() throws Exception {
        String role = scoped().path("id").asText();
        String path = base + "/roles/" + role + "/permissions/preview";
        error(
                business.post(
                        path,
                        obj(
                                "expectedVersion",
                                0,
                                "permissions",
                                List.of(
                                        obj(
                                                "permissionId",
                                                permission,
                                                "scope",
                                                all(),
                                                "validFrom",
                                                "2030-01-02T00:00:00Z",
                                                "validTo",
                                                "2030-01-01T00:00:00Z")))),
                422,
                "INVALID_VALIDITY");
        error(
                business.post(
                        path,
                        obj(
                                "expectedVersion",
                                0,
                                "permissions",
                                List.of(
                                        obj("permissionId", permission, "scope", all()),
                                        obj("permissionId", permission, "scope", all())))),
                422,
                "DUPLICATE_ITEM");
        for (var scope :
                List.of(
                        obj("kind", "ORG", "organizationIds", List.of()),
                        obj("kind", "SELF", "organizationIds", List.of("999")),
                        obj("kind", "ORG_SUBTREE", "organizationIds", List.of("999"))))
            assertThat(
                            business.post(
                                            path,
                                            obj(
                                                    "expectedVersion",
                                                    0,
                                                    "permissions",
                                                    List.of(
                                                            obj(
                                                                    "permissionId",
                                                                    permission,
                                                                    "scope",
                                                                    scope))))
                                    .status())
                    .isEqualTo(422);
        assertThat(
                        business.post(
                                        base + "/roles",
                                        obj(
                                                "code",
                                                "common-fake",
                                                "name",
                                                "Fake",
                                                "kind",
                                                "COMMON",
                                                "ownerUserId",
                                                null,
                                                "personnelScope",
                                                peopleAll()))
                                .status())
                .isEqualTo(403);
        assertThat(
                        admin.post(
                                        base + "/roles",
                                        obj(
                                                "code",
                                                "platform-fake",
                                                "name",
                                                "Fake",
                                                "kind",
                                                "PLATFORM",
                                                "ownerUserId",
                                                null,
                                                "personnelScope",
                                                peopleAll()))
                                .status())
                .isEqualTo(422);
        assertThat(
                        admin.post(
                                        base + "/delegations",
                                        obj(
                                                "administratorUserId",
                                                employee,
                                                "enabled",
                                                true,
                                                "peopleScope",
                                                peopleAll(),
                                                "commonRoleIds",
                                                List.of(rid("demo_pmis")),
                                                "permissionCeilings",
                                                businessPoint(),
                                                "expectedVersion",
                                                null))
                                .status())
                .isEqualTo(404);
        assertThat(
                        admin.post(
                                        base + "/delegations",
                                        obj(
                                                "administratorUserId",
                                                employee,
                                                "enabled",
                                                true,
                                                "peopleScope",
                                                peopleAll(),
                                                "commonRoleIds",
                                                List.of(demoRole),
                                                "permissionCeilings",
                                                businessPoint(),
                                                "expectedVersion",
                                                2))
                                .status())
                .isEqualTo(409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?",
                                Integer.class,
                                role))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_change_preview", Integer.class))
                .isZero();
    }

    @Test
    void scopedOrganizationChoicesAndEmployeeAccountLookupExposeOnlyAuthorizedMetadata()
            throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String dept = createOrg("department-a", root).path("id").asText();
        String other = createOrg("department-b", root).path("id").asText();
        String child = createOrg("department-child", dept).path("id").asText();
        assertThat(business.get(base + "/organizations").status()).isEqualTo(403);
        var scope =
                obj(
                        "allCompany",
                        false,
                        "organizations",
                        List.of(obj("organizationId", dept, "includeDescendants", false)));
        var request =
                obj(
                        "administratorUserId",
                        oaAdmin,
                        "enabled",
                        true,
                        "peopleScope",
                        scope,
                        "commonRoleIds",
                        List.of(demoRole),
                        "permissionCeilings",
                        List.of(
                                obj(
                                        "permissionId",
                                        permission,
                                        "scope",
                                        obj("kind", "SELF", "organizationIds", List.of()))),
                        "expectedVersion",
                        null);
        var d = success(admin.post(base + "/delegations", request), 201);
        var choices = success(business.get(base + "/organizations"), 200).path("items");
        assertThat(choices.findValuesAsText("id")).containsExactly(root, dept);
        assertThat(choices.get(0).path("peopleSelectable").asBoolean()).isFalse();
        assertThat(choices.get(1).path("peopleSelectable").asBoolean()).isTrue();
        assertThat(choices.get(1).path("peopleSubtreeAllowed").asBoolean()).isFalse();
        request.put(
                "peopleScope",
                obj(
                        "allCompany",
                        false,
                        "organizations",
                        List.of(obj("organizationId", dept, "includeDescendants", true))));
        request.put("expectedVersion", 0);
        success(
                admin.request("PUT", base + "/delegations/" + d.path("id").asText(), request, true),
                200);
        assertThat(
                        success(business.get(base + "/organizations"), 200)
                                .path("items")
                                .findValuesAsText("id"))
                .containsExactly(root, dept, child);
        assertThat(success(admin.get(base + "/organizations"), 200).path("items").size())
                .isEqualTo(4);
        request.put("peopleScope", scope);
        request.put("permissionCeilings", businessPoint());
        request.put("expectedVersion", 1);
        success(
                admin.request("PUT", base + "/delegations/" + d.path("id").asText(), request, true),
                200);
        assertThat(
                        success(business.get(base + "/organizations"), 200)
                                .path("items")
                                .findValuesAsText("id"))
                .contains(other);
        String e = createEmployee("lookup-person", dept).path("id").asText();
        assertThat(success(admin.get("/api/v1/admin/employees/" + e + "/account"), 200).isNull())
                .isTrue();
        String u = createAccount("lookup-user", e).path("id").asText();
        assertThat(
                        success(admin.get("/api/v1/admin/employees/" + e + "/account"), 200)
                                .path("id")
                                .asText())
                .isEqualTo(u);
        assertThat(business.get("/api/v1/admin/employees/" + e + "/account").status())
                .isEqualTo(403);
    }

    @Test
    void personnelWithoutAccountCanMoveButCannotReceiveRoles() throws Exception {
        String root = createOrg("head-office", null).path("id").asText();
        String dept = createOrg("department-a", root).path("id").asText();
        String e = createEmployee("no-account-person", root).path("id").asText();
        var invalid =
                obj(
                        "expectedVersion",
                        0,
                        "expectedAccountVersion",
                        null,
                        "organizationId",
                        dept,
                        "positionName",
                        null,
                        "managementAction",
                        "KEEP",
                        "applications",
                        List.of(
                                obj(
                                        "applicationId",
                                        "2",
                                        "memberships",
                                        member(demoRole),
                                        "responsibilityDelegationId",
                                        null)));
        error(
                admin.post("/api/v1/admin/employees/" + e + "/assignment-preview", invalid),
                422,
                "ACCOUNT_REQUIRED");
        var command =
                obj(
                        "expectedVersion",
                        0,
                        "expectedAccountVersion",
                        null,
                        "organizationId",
                        dept,
                        "positionName",
                        null,
                        "managementAction",
                        "KEEP",
                        "applications",
                        List.of());
        var p =
                success(
                        admin.post("/api/v1/admin/employees/" + e + "/assignment-preview", command),
                        200);
        var result =
                success(
                        admin.request(
                                "PUT",
                                "/api/v1/admin/employees/" + e + "/assignment",
                                obj("previewToken", p.path("token").asText()),
                                true),
                        200);
        assertThat(result.path("userId").isNull()).isTrue();
        assertThat(result.path("employee").path("organizationId").asText()).isEqualTo(dept);
        assertThat(result.path("applications").size()).isZero();
    }
}

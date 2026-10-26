/** 功能：隔离MySQL/Redis中通过真实HTTP验证应用委派、增量上限、人员责任与原子换角。 */
package com.municipal.rbac.bootstrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.*;
import com.municipal.rbac.audit.AuditLog;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ResourceBusinessIntegrationTest {
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
    String company, deptA, deptB, child, writerA, writerB, writerChild, reviewerA, reviewerB;
    Client business;
    String base = "/api/v1/admin/applications/2";

    @BeforeEach
    void setup() throws Exception {
        // 仅清理本类新建容器内合成关系；遵循RESTRICT外键顺序，不连接本机库。
        for (String table :
                List.of(
                        "oa_document_event",
                        "oa_document",
                        "pmis_project_event",
                        "pmis_project",
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
        db.update("UPDATE auth_application SET enabled=TRUE");
        db.update("UPDATE auth_resource SET enabled=TRUE");
        db.update("UPDATE auth_permission SET enabled=TRUE");
        admin = new Client();
        success(admin.login("super-admin", "1234"), 200);
        initializeCatalog();
        company = organization("company", null, "HEADQUARTERS");
        deptA = organization("department-a", company, "DEPARTMENT");
        deptB = organization("department-b", company, "DEPARTMENT");
        child = organization("child-department", deptA, "DEPARTMENT");
        writerA = person("writer-a", deptA);
        writerB = person("writer-b", deptB);
        writerChild = person("writer-child", child);
        reviewerA = person("reviewer-a", company);
        reviewerB = person("reviewer-b", company);
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

    String app(String app) {
        return app.equals("oa") ? "2" : "3";
    }

    String resource(String app) {
        return app.equals("oa") ? "document" : "project";
    }

    String url(String app) {
        return "/api/v1/" + app + "/" + (app.equals("oa") ? "documents" : "projects");
    }

    Map<String, Object> scope(String kind, String... ids) {
        return obj("kind", kind, "organizationIds", List.of(ids));
    }

    String organization(String code, String parent, String kind) throws Exception {
        return success(
                        admin.post(
                                "/api/v1/admin/organizations",
                                obj(
                                        "code",
                                        code,
                                        "name",
                                        code,
                                        "parentId",
                                        parent,
                                        "nodeType",
                                        kind)),
                        201)
                .path("id")
                .asText();
    }

    String person(String username, String org) throws Exception {
        String e =
                success(
                                admin.post(
                                        "/api/v1/admin/employees",
                                        obj(
                                                "employeeCode",
                                                username,
                                                "displayName",
                                                username,
                                                "organizationId",
                                                org,
                                                "positionName",
                                                "测试岗位",
                                                "phone",
                                                null)),
                                201)
                        .path("id")
                        .asText();
        success(
                admin.post("/api/v1/admin/accounts", obj("username", username, "employeeId", e)),
                201);
        return uid(username);
    }

    void initializeCatalog() throws Exception {
        for (String application : List.of("oa", "pmis")) {
            String a = app(application),
                    resourceId =
                            db.queryForObject(
                                    "SELECT id FROM auth_resource WHERE application_id=? AND code=?",
                                    String.class,
                                    a,
                                    resource(application));
            for (String action :
                    List.of(
                            "create",
                            "update",
                            "submit",
                            "withdraw",
                            "approve",
                            "reject",
                            application.equals("oa") ? "void" : "cancel"))
                if (db.queryForObject(
                                "SELECT COUNT(*) FROM auth_permission WHERE code=?",
                                Integer.class,
                                application + ":" + resource(application) + ":" + action)
                        == 0)
                    success(
                            admin.post(
                                    "/api/v1/admin/applications/" + a + "/permissions",
                                    obj(
                                            "resourceId",
                                            resourceId,
                                            "action",
                                            action,
                                            "name",
                                            action,
                                            "description",
                                            "测试已实现动作")),
                            201);
        }
    }

    String role(String application, String who, Map<String, Object> range, String... actions)
            throws Exception {
        String a = app(application), base = "/api/v1/admin/applications/" + a;
        String code = "case-" + UUID.randomUUID().toString().substring(0, 12);
        var r =
                success(
                        admin.post(
                                base + "/roles",
                                obj(
                                        "code",
                                        code,
                                        "name",
                                        code,
                                        "kind",
                                        "COMMON",
                                        "ownerUserId",
                                        null,
                                        "personnelScope",
                                        obj("allCompany", true, "organizations", List.of()))),
                        201);
        String id = r.path("id").asText();
        List<Object> perms = new ArrayList<>();
        for (String action : actions)
            perms.add(
                    obj(
                            "permissionId",
                            pid(application + ":" + resource(application) + ":" + action),
                            "scope",
                            range,
                            "validFrom",
                            null,
                            "validTo",
                            null));
        var preview =
                success(
                        admin.post(
                                base + "/roles/" + id + "/permissions/preview",
                                obj("expectedVersion", 0, "permissions", perms)),
                        200);
        success(
                admin.request(
                        "PUT",
                        base + "/roles/" + id + "/permissions",
                        obj("previewToken", preview.path("token").asText()),
                        true),
                200);
        var current = success(admin.get(base + "/users/" + who + "/roles"), 200);
        List<Object> memberships = new ArrayList<>();
        for (var membership : current.path("memberships"))
            memberships.add(
                    obj(
                            "roleId",
                            membership.path("roleId").asText(),
                            "validFrom",
                            null,
                            "validTo",
                            null));
        memberships.add(obj("roleId", id, "validFrom", null, "validTo", null));
        assign(application, who, current.path("version").asLong(), memberships);
        return id;
    }

    void assign(String application, String user, long version, List<?> memberships)
            throws Exception {
        String path =
                "/api/v1/admin/applications/" + app(application) + "/users/" + user + "/roles";
        var preview =
                success(
                        admin.post(
                                path + "/preview",
                                obj("expectedVersion", version, "memberships", memberships)),
                        200);
        success(
                admin.request(
                        "PUT", path, obj("previewToken", preview.path("token").asText()), true),
                200);
    }

    void employeeRole(String app, String user) throws Exception {
        role(
                app,
                user,
                scope("SELF"),
                "view",
                "create",
                "update",
                "submit",
                "withdraw",
                app.equals("oa") ? "void" : "cancel");
    }

    void reviewerRole(String app, String user) throws Exception {
        role(app, user, scope("ALL"), "view", "approve", "reject");
    }

    Client client(String username) throws Exception {
        Client c = new Client();
        success(c.login(username, "1234"), 200);
        return c;
    }

    Map<String, Object> draft(String application, String title) {
        return application.equals("oa")
                ? obj("title", title, "body", "测试公文正文")
                : obj(
                        "name",
                        title,
                        "description",
                        "测试立项说明",
                        "plannedStart",
                        "2026-10-26",
                        "plannedEnd",
                        "2026-11-26",
                        "budget",
                        "10000.00");
    }

    JsonNode create(Client c, String app, String title) throws Exception {
        return success(c.post(url(app), draft(app, title)), 201);
    }

    JsonNode act(
            Client c, String app, JsonNode record, String action, String approver, String comment)
            throws Exception {
        Map<String, Object> command = obj("expectedVersion", record.path("version").asLong());
        if (action.equals("submit")) command.put("approverUserId", approver);
        if (List.of("approve", "reject").contains(action)) command.put("comment", comment);
        return success(
                c.post(url(app) + "/" + record.path("id").asText() + "/" + action, command), 200);
    }

    String resource(String application, String parent, String code, String kind) throws Exception {
        return success(
                        admin.post(
                                "/api/v1/admin/applications/" + app(application) + "/resources",
                                obj(
                                        "parentId",
                                        parent,
                                        "code",
                                        code,
                                        "name",
                                        code,
                                        "kind",
                                        kind,
                                        "description",
                                        "测试目录")),
                        201)
                .path("id")
                .asText();
    }

    String unique() {
        return "resource-" + UUID.randomUUID().toString().substring(0, 12);
    }

    JsonNode catalog(String app) throws Exception {
        return success(admin.get("/api/v1/admin/applications/" + app(app) + "/catalog"), 200);
    }

    JsonNode statusPreview(String base, boolean enabled, long version) throws Exception {
        return success(
                admin.post(
                        base + "/status-preview",
                        obj("enabled", enabled, "expectedVersion", version)),
                200);
    }

    Response statusCommit(String base, JsonNode preview) throws Exception {
        return admin.request(
                "PUT", base + "/status", obj("previewToken", preview.path("token").asText()), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void realWorkflowSupportsRejectedEditingWithdrawalAndDesignatedApproval(String application)
            throws Exception {
        employeeRole(application, writerA);
        reviewerRole(application, reviewerA);
        reviewerRole(application, reviewerB);
        Client owner = client("writer-a"),
                reviewer = client("reviewer-a"),
                other = client("reviewer-b");
        JsonNode d = create(owner, application, "完整流程");
        String base = url(application) + "/" + d.path("id").asText();
        Map<String, Object> edit = draft(application, "修改后的记录");
        edit.put("expectedVersion", d.path("version").asLong());
        d = success(owner.request("PUT", base, edit, true), 200);
        assertThat(success(owner.get(base + "/approvers"), 200).path("items")).hasSize(2);
        error(
                owner.post(
                        base + "/submit",
                        obj(
                                "expectedVersion",
                                d.path("version").asLong(),
                                "approverUserId",
                                writerA)),
                422,
                "SELF_APPROVAL");
        error(
                owner.post(
                        base + "/submit",
                        obj(
                                "expectedVersion",
                                d.path("version").asLong(),
                                "approverUserId",
                                writerB)),
                422,
                "APPROVER_UNAVAILABLE");
        d = act(owner, application, d, "submit", reviewerA, "");
        error(
                other.post(
                        base + "/approve",
                        obj("expectedVersion", d.path("version").asLong(), "comment", "")),
                403,
                "DESIGNATED_APPROVER_REQUIRED");
        error(
                reviewer.post(
                        base + "/reject",
                        obj("expectedVersion", d.path("version").asLong(), "comment", " ")),
                422,
                "REJECTION_COMMENT_REQUIRED");
        d = act(reviewer, application, d, "reject", null, "补充说明后重提");
        edit.put("expectedVersion", d.path("version").asLong());
        d = success(owner.request("PUT", base, edit, true), 200);
        d = act(owner, application, d, "submit", reviewerA, "");
        d = act(owner, application, d, "withdraw", null, "");
        d = act(owner, application, d, "submit", reviewerB, "");
        d = act(other, application, d, "approve", null, "同意");
        assertThat(d.path("status").asText()).isEqualTo("APPROVED");
        edit.put("expectedVersion", d.path("version").asLong());
        error(owner.request("PUT", base, edit, true), 409, "BUSINESS_STATE_CONFLICT");
        assertThat(success(owner.get(base + "/history"), 200).path("items")).hasSize(9);
        success(owner.get("/api/v1/" + application + "/context"), 200);
        assertThat(
                        success(other.get(url(application) + "?pendingForMe=true"), 200)
                                .path("total")
                                .asInt())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void terminalCancellationInputAndCsrfRules(String application) throws Exception {
        employeeRole(application, writerA);
        Client c = client("writer-a");
        JsonNode d = create(c, application, "终态");
        String base = url(application) + "/" + d.path("id").asText();
        error(
                c.request("POST", url(application), draft(application, "无CSRF"), false),
                403,
                "FORBIDDEN");
        error(
                c.post(base + "/withdraw", obj("expectedVersion", 0)),
                409,
                "BUSINESS_STATE_CONFLICT");
        Map<String, Object> bad = draft(application, " ");
        assertThat(c.post(url(application), bad).status()).isEqualTo(400);
        bad = draft(application, "伪造归属");
        bad.put("ownerUserId", writerB);
        assertThat(c.post(url(application), bad).status()).isEqualTo(400);
        assertThat(c.get(url(application) + "/not-id").status()).isEqualTo(400);
        assertThat(c.get(url(application) + "?page=0").status()).isEqualTo(400);
        assertThat(c.get(url(application) + "?status=INVALID").status()).isEqualTo(400);
        assertThat(c.get(url(application) + "?q=" + "x".repeat(101)).status()).isEqualTo(400);
        d = act(c, application, d, application.equals("oa") ? "void" : "cancel", null, "");
        assertThat(d.path("status").asText())
                .isEqualTo(application.equals("oa") ? "VOIDED" : "CANCELLED");
        Map<String, Object> edit = draft(application, "终态不可改");
        edit.put("expectedVersion", d.path("version").asLong());
        error(c.request("PUT", base, edit, true), 409, "BUSINESS_STATE_CONFLICT");
        assertThat(new Client().get(url(application)).status()).isEqualTo(401);
        assertThat(c.get(url(application) + "?mine=true&q=%25").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void selfScopeFiltersListsDetailsAndDoesNotTurnAllScopeIntoOwnership(String application)
            throws Exception {
        employeeRole(application, writerA);
        employeeRole(application, writerB);
        Client a = client("writer-a"), b = client("writer-b");
        JsonNode da = create(a, application, "本人甲"), dbb = create(b, application, "本人乙");
        assertThat(success(a.get(url(application)), 200).path("total").asInt()).isEqualTo(1);
        error(
                a.get(url(application) + "/" + dbb.path("id").asText()),
                404,
                "BUSINESS_RECORD_NOT_FOUND");
        role(application, writerA, scope("ALL"), "update");
        Map<String, Object> edit = draft(application, "他人记录");
        edit.put("expectedVersion", 0);
        error(
                a.request("PUT", url(application) + "/" + dbb.path("id").asText(), edit, true),
                403,
                "OWNER_REQUIRED");
        assertThat(admin.get(url(application)).status()).isEqualTo(403);
        assertThat(
                        success(a.get(url(application) + "?mine=true"), 200)
                                .path("items")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(da.path("id").asText());
        assertThat(
                        a.get(
                                        "/api/v1/"
                                                + (application.equals("oa")
                                                        ? "pmis/projects"
                                                        : "oa/documents"))
                                .status())
                .isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void organizationExactSubtreeAndUnionRangesAreAppliedBeforeSqlPaging(String application)
            throws Exception {
        employeeRole(application, writerA);
        employeeRole(application, writerB);
        employeeRole(application, writerChild);
        JsonNode a = create(client("writer-a"), application, "部门甲"),
                b = create(client("writer-b"), application, "部门乙"),
                c = create(client("writer-child"), application, "甲子部门");
        role(application, reviewerA, scope("ORG", deptA), "view");
        Client reader = client("reviewer-a");
        assertThat(success(reader.get(url(application)), 200).path("total").asInt()).isEqualTo(1);
        error(
                reader.get(url(application) + "/" + c.path("id").asText()),
                404,
                "BUSINESS_RECORD_NOT_FOUND");
        role(application, reviewerA, scope("ORG_SUBTREE", deptA), "view");
        assertThat(
                        success(reader.get(url(application) + "?size=1&page=1"), 200)
                                .path("total")
                                .asInt())
                .isEqualTo(2);
        success(reader.get(url(application) + "/" + c.path("id").asText()), 200);
        error(
                reader.get(url(application) + "/" + b.path("id").asText()),
                404,
                "BUSINESS_RECORD_NOT_FOUND");
        role(application, reviewerA, scope("ALL"), "view");
        assertThat(success(reader.get(url(application)), 200).path("total").asInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void revokedApproverCanNoLongerProcessAndOwnerCanWithdraw(String application) throws Exception {
        employeeRole(application, writerA);
        reviewerRole(application, reviewerA);
        Client c = client("writer-a"), r = client("reviewer-a");
        JsonNode d = act(c, application, create(c, application, "审批人失权"), "submit", reviewerA, "");
        var roles =
                success(
                        admin.get(
                                "/api/v1/admin/applications/"
                                        + app(application)
                                        + "/users/"
                                        + reviewerA
                                        + "/roles"),
                        200);
        assign(application, reviewerA, roles.path("version").asLong(), List.of());
        assertThat(
                        r.post(
                                        url(application) + "/" + d.path("id").asText() + "/approve",
                                        obj(
                                                "expectedVersion",
                                                d.path("version").asLong(),
                                                "comment",
                                                "同意"))
                                .status())
                .isEqualTo(403);
        d = act(c, application, d, "withdraw", null, "");
        assertThat(d.path("status").asText()).isEqualTo("DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void approvalAndWithdrawalAreSerializedAndOnlyOneCanCommit(String application)
            throws Exception {
        employeeRole(application, writerA);
        reviewerRole(application, reviewerA);
        Client c = client("writer-a"), r = client("reviewer-a");
        JsonNode d = act(c, application, create(c, application, "并发"), "submit", reviewerA, "");
        String path = url(application) + "/" + d.path("id").asText();
        long v = d.path("version").asLong();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve =
                    pool.submit(
                            () ->
                                    r.post(
                                                    path + "/approve",
                                                    obj("expectedVersion", v, "comment", "同意"))
                                            .status());
            var withdraw =
                    pool.submit(
                            () -> c.post(path + "/withdraw", obj("expectedVersion", v)).status());
            assertThat(List.of(approve.get(), withdraw.get())).containsExactlyInAnyOrder(200, 409);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void auditFailureRollsBackRecordAndHistory(String application) throws Exception {
        employeeRole(application, writerA);
        Client c = client("writer-a");
        JsonNode d = create(c, application, "审计回滚");
        long id = d.path("id").asLong();
        doThrow(new DataAccessResourceFailureException("synthetic audit outage"))
                .when(audit)
                .append(anyLong(), any(), any(), any(), anyLong(), any(), any());
        try {
            assertThat(
                            c.post(
                                            url(application)
                                                    + "/"
                                                    + id
                                                    + "/"
                                                    + (application.equals("oa")
                                                            ? "void"
                                                            : "cancel"),
                                            obj("expectedVersion", 0))
                                    .status())
                    .isEqualTo(503);
        } finally {
            reset(audit);
        }
        assertThat(success(c.get(url(application) + "/" + id), 200).path("status").asText())
                .isEqualTo("DRAFT");
        assertThat(success(c.get(url(application) + "/" + id + "/history"), 200).path("items"))
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"oa", "pmis"})
    void permissionAndAncestorDisableImmediatelyRevokeAndPreviewedRestoreResumes(String application)
            throws Exception {
        employeeRole(application, writerA);
        Client c = client("writer-a");
        create(c, application, "实时停用");
        String permission = pid(application + ":" + resource(application) + ":view"),
                base =
                        "/api/v1/admin/applications/"
                                + app(application)
                                + "/permissions/"
                                + permission;
        long version =
                db.queryForObject(
                        "SELECT version FROM auth_permission WHERE id=?", Long.class, permission);
        success(statusCommit(base, statusPreview(base, false, version)), 200);
        assertThat(c.get(url(application)).status()).isEqualTo(403);
        success(statusCommit(base, statusPreview(base, true, version + 1)), 200);
        success(c.get(url(application)), 200);
        String parent = resource(application, null, unique(), "DIRECTORY"),
                node =
                        db.queryForObject(
                                "SELECT resource_id FROM auth_permission WHERE id=?",
                                String.class,
                                permission);
        var current = catalog(application).path("resources");
        long rv = 0;
        for (var n : current)
            if (n.path("id").asText().equals(node)) rv = n.path("version").asLong();
        success(
                admin.request(
                        "PUT",
                        "/api/v1/admin/applications/" + app(application) + "/resources/" + node,
                        obj(
                                "parentId",
                                parent,
                                "name",
                                resource(application),
                                "description",
                                "分组",
                                "expectedVersion",
                                rv),
                        true),
                200);
        String resourceBase =
                "/api/v1/admin/applications/" + app(application) + "/resources/" + parent;
        success(statusCommit(resourceBase, statusPreview(resourceBase, false, 0)), 200);
        assertThat(c.get(url(application)).status()).isEqualTo(403);
        success(statusCommit(resourceBase, statusPreview(resourceBase, true, 1)), 200);
        success(c.get(url(application)), 200);
    }

    @Test
    void movingReferencedResourcesCannotBypassDisableAndRestorePreview() throws Exception {
        employeeRole("oa", writerA);
        Client reader = client("writer-a");
        String node =
                db.queryForObject(
                        "SELECT resource_id FROM auth_permission WHERE code='oa:document:view'",
                        String.class);
        String folder = resource("oa", null, unique(), "DIRECTORY");
        String base = "/api/v1/admin/applications/2/resources/", resourcePath = base + node;
        success(statusCommit(base + folder, statusPreview(base + folder, false, 0)), 200);
        long v =
                db.queryForObject("SELECT version FROM auth_resource WHERE id=?", Long.class, node);
        error(
                admin.request(
                        "PUT",
                        resourcePath,
                        obj(
                                "parentId",
                                folder,
                                "name",
                                "公文功能",
                                "description",
                                "",
                                "expectedVersion",
                                v),
                        true),
                409,
                "RESOURCE_ACTIVATION_CHANGE");
        success(statusCommit(resourcePath, statusPreview(resourcePath, false, v)), 200);
        success(
                admin.request(
                        "PUT",
                        resourcePath,
                        obj(
                                "parentId",
                                folder,
                                "name",
                                "公文功能",
                                "description",
                                "",
                                "expectedVersion",
                                v + 1),
                        true),
                200);
        success(statusCommit(resourcePath, statusPreview(resourcePath, true, v + 2)), 200);
        assertThat(reader.get(url("oa")).status()).isEqualTo(403);
        error(
                admin.request(
                        "PUT",
                        resourcePath,
                        obj(
                                "parentId",
                                null,
                                "name",
                                "公文功能",
                                "description",
                                "",
                                "expectedVersion",
                                v + 3),
                        true),
                409,
                "RESOURCE_ACTIVATION_CHANGE");
        success(statusCommit(resourcePath, statusPreview(resourcePath, false, v + 3)), 200);
        success(
                admin.request(
                        "PUT",
                        resourcePath,
                        obj(
                                "parentId",
                                null,
                                "name",
                                "公文功能",
                                "description",
                                "",
                                "expectedVersion",
                                v + 4),
                        true),
                200);
        success(statusCommit(resourcePath, statusPreview(resourcePath, true, v + 5)), 200);
        success(reader.get(url("oa")), 200);
    }

    @Test
    void pmisRejectsReversedDatesAndInvalidBudget() throws Exception {
        employeeRole("pmis", writerA);
        Client c = client("writer-a");
        var r = draft("pmis", "非法日期");
        r.put("plannedEnd", "2026-01-01");
        error(c.post(url("pmis"), r), 422, "PROJECT_DATES");
        r = draft("pmis", "非法金额");
        r.put("budget", "-1.00");
        assertThat(c.post(url("pmis"), r).status()).isEqualTo(400);
        r.put("budget", "1.001");
        assertThat(c.post(url("pmis"), r).status()).isEqualTo(400);
    }

    @Test
    void applicationCatalogCanBeCreatedEditedDisabledAndRestoredWithoutGrantingUsers()
            throws Exception {
        String code = "app-" + UUID.randomUUID().toString().substring(0, 8);
        var a =
                success(
                        admin.post(
                                "/api/v1/admin/catalog/applications",
                                obj("code", code, "name", "登记应用", "description", "仅目录")),
                        201);
        String path = "/api/v1/admin/catalog/applications/" + a.path("id").asText();
        success(
                admin.request(
                        "PUT",
                        path,
                        obj("name", "已编辑应用", "description", "未接入业务", "expectedVersion", 0),
                        true),
                200);
        success(statusCommit(path, statusPreview(path, false, 1)), 200);
        success(statusCommit(path, statusPreview(path, true, 2)), 200);
        success(admin.get("/api/v1/admin/catalog/applications"), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_role WHERE application_id=?",
                                Integer.class,
                                a.path("id").asText()))
                .isZero();
        assertThat(
                        admin.post(
                                        "/api/v1/admin/catalog/applications",
                                        obj("code", code, "name", "重复", "description", ""))
                                .status())
                .isEqualTo(409);
    }

    @Test
    void catalogResourcesRejectCyclesNinthLevelFunctionalParentsAndCrossApplicationParents()
            throws Exception {
        String top = resource("oa", null, unique(), "DIRECTORY"), last = top;
        for (int i = 1; i < 8; i++) last = resource("oa", last, unique(), "DIRECTORY");
        error(
                admin.post(
                        "/api/v1/admin/applications/2/resources",
                        obj(
                                "parentId",
                                last,
                                "code",
                                unique(),
                                "name",
                                "第九层",
                                "kind",
                                "FUNCTION",
                                "description",
                                "")),
                422,
                "RESOURCE_DEPTH");
        error(
                admin.request(
                        "PUT",
                        "/api/v1/admin/applications/2/resources/" + top,
                        obj(
                                "parentId",
                                last,
                                "name",
                                "成环",
                                "description",
                                "",
                                "expectedVersion",
                                0),
                        true),
                422,
                "RESOURCE_CYCLE");
        String function = resource("oa", null, unique(), "FUNCTION");
        error(
                admin.post(
                        "/api/v1/admin/applications/2/resources",
                        obj(
                                "parentId",
                                function,
                                "code",
                                unique(),
                                "name",
                                "非法父节点",
                                "kind",
                                "DIRECTORY",
                                "description",
                                "")),
                422,
                "RESOURCE_PARENT_TYPE");
        assertThat(
                        admin.post(
                                        "/api/v1/admin/applications/3/resources",
                                        obj(
                                                "parentId",
                                                top,
                                                "code",
                                                unique(),
                                                "name",
                                                "跨应用",
                                                "kind",
                                                "FUNCTION",
                                                "description",
                                                ""))
                                .status())
                .isEqualTo(404);
        assertThat(
                        admin.post(
                                        "/api/v1/admin/applications/2/resources",
                                        obj(
                                                "parentId",
                                                null,
                                                "code",
                                                unique(),
                                                "name",
                                                "非法类型",
                                                "kind",
                                                "OTHER",
                                                "description",
                                                ""))
                                .status())
                .isEqualTo(422);
    }

    @Test
    void unknownPermissionStaysPlannedAndCannotBeGrantedNorWrittenByBusinessAdministrator()
            throws Exception {
        String code = unique(),
                function = resource("oa", null, code, "FUNCTION"),
                base = "/api/v1/admin/applications/2";
        var p =
                success(
                        admin.post(
                                base + "/permissions",
                                obj(
                                        "resourceId",
                                        function,
                                        "action",
                                        "view",
                                        "name",
                                        "待接入",
                                        "description",
                                        "未知编码")),
                        201);
        assertThat(p.path("implementationStatus").asText()).isEqualTo("PLANNED");
        success(
                admin.request(
                        "PUT",
                        base + "/permissions/" + p.path("id").asText(),
                        obj("name", "改名", "description", "仍待接入", "expectedVersion", 0),
                        true),
                200);
        String r = role("oa", writerA, scope("SELF"), "view");
        long v =
                success(admin.get(base + "/roles/" + r + "/permissions"), 200)
                        .path("version")
                        .asLong();
        assertThat(
                        admin.post(
                                        base + "/roles/" + r + "/permissions/preview",
                                        obj(
                                                "expectedVersion",
                                                v,
                                                "permissions",
                                                List.of(
                                                        obj(
                                                                "permissionId",
                                                                p.path("id").asText(),
                                                                "scope",
                                                                scope("ALL"),
                                                                "validFrom",
                                                                null,
                                                                "validTo",
                                                                null))))
                                .status())
                .isEqualTo(422);
        Client business = client("oa-admin");
        assertThat(
                        business.post(
                                        base + "/permissions",
                                        obj(
                                                "resourceId",
                                                function,
                                                "action",
                                                "update",
                                                "name",
                                                "越权",
                                                "description",
                                                ""))
                                .status())
                .isEqualTo(403);
        assertThat(
                        admin.post(
                                        base + "/permissions",
                                        obj(
                                                "resourceId",
                                                function,
                                                "action",
                                                "view",
                                                "name",
                                                "重复",
                                                "description",
                                                ""))
                                .status())
                .isEqualTo(409);
    }

    @Test
    void previewIsVersionedSingleUseActorBoundAndPlatformRootIsProtected() throws Exception {
        String node = resource("oa", null, unique(), "FUNCTION"),
                base = "/api/v1/admin/applications/2/resources/" + node;
        JsonNode p = statusPreview(base, false, 0);
        success(
                admin.request(
                        "PUT",
                        base,
                        obj(
                                "parentId",
                                null,
                                "name",
                                "已变更",
                                "description",
                                "",
                                "expectedVersion",
                                0),
                        true),
                200);
        assertThat(statusCommit(base, p).status()).isEqualTo(409);
        p = statusPreview(base, false, 1);
        success(statusCommit(base, p), 200);
        assertThat(statusCommit(base, p).status()).isEqualTo(409);
        p = statusPreview(base, true, 2);
        db.update(
                "UPDATE auth_change_preview SET expires_at='2000-01-01' WHERE token=?",
                p.path("token").asText());
        assertThat(statusCommit(base, p).status()).isEqualTo(410);
        assertThat(
                        admin.post(
                                        "/api/v1/admin/catalog/applications/1/status-preview",
                                        obj("enabled", false, "expectedVersion", 0))
                                .status())
                .isEqualTo(422);
        assertThat(
                        admin.post(
                                        "/api/v1/admin/applications/1/permissions/"
                                                + pid("admin:platform:manage")
                                                + "/status-preview",
                                        obj("enabled", false, "expectedVersion", 0))
                                .status())
                .isEqualTo(422);
    }

    @Test
    void applicationDisableRevokesBusinessAndRestorationPreservesExistingMemberships()
            throws Exception {
        employeeRole("oa", writerA);
        Client c = client("writer-a");
        create(c, "oa", "应用启停");
        String base = "/api/v1/admin/catalog/applications/2";
        long v = db.queryForObject("SELECT version FROM auth_application WHERE id=2", Long.class);
        long before =
                db.queryForObject(
                        "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?", Long.class, writerA);
        success(statusCommit(base, statusPreview(base, false, v)), 200);
        assertThat(c.get(url("oa")).status()).isEqualTo(403);
        success(statusCommit(base, statusPreview(base, true, v + 1)), 200);
        success(c.get(url("oa")), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM auth_user_role WHERE user_id=?",
                                Long.class,
                                writerA))
                .isEqualTo(before);
    }

    @Test
    void freshSchemaAndV3UpgradeHaveTheSameTablesAndPreserveOldChecksums() throws Exception {
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                                Integer.class))
                .isEqualTo(31);
        try (var container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.7"))) {
            container.start();
            var old =
                    Flyway.configure()
                            .dataSource(
                                    container.getJdbcUrl(),
                                    container.getUsername(),
                                    container.getPassword())
                            .target("3")
                            .load();
            old.migrate();
            var sql =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    container.getJdbcUrl(),
                                    container.getUsername(),
                                    container.getPassword()));
            var checks =
                    sql.queryForList(
                            "SELECT version,checksum FROM flyway_schema_history WHERE version IN('1','2','3') ORDER BY version");
            var upgrade =
                    Flyway.configure()
                            .dataSource(
                                    container.getJdbcUrl(),
                                    container.getUsername(),
                                    container.getPassword())
                            .load()
                            .migrate();
            assertThat(upgrade.migrationsExecuted).isEqualTo(1);
            assertThat(
                            sql.queryForList(
                                    "SELECT version,checksum FROM flyway_schema_history WHERE version IN('1','2','3') ORDER BY version"))
                    .isEqualTo(checks);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                                    Integer.class))
                    .isEqualTo(31);
        }
    }
}

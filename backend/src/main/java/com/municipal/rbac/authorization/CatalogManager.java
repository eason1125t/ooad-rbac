/** 功能：平台目录管理、8层资源树及启停影响预览；与V4、CatalogMapper及既有授权命名空间锁协作。 */
package com.municipal.rbac.authorization;

import static com.municipal.rbac.identity.IdentityData.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.municipal.rbac.audit.AuditLog;
import com.municipal.rbac.authorization.AccessData.Actor;
import com.municipal.rbac.authorization.AccessData.Commit;
import com.municipal.rbac.authorization.AccessData.Status;
import com.municipal.rbac.authorization.CatalogData.*;
import com.municipal.rbac.authorization.internal.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class CatalogManager {
    private static final String OP = "admin:catalog:manage";
    private final CatalogMapper catalog;
    private final AccessMapper access;
    private final PermissionChecker checker;
    private final AuditLog audit;
    private final Clock clock;
    private final ObjectMapper json;

    public CatalogManager(
            CatalogMapper catalog,
            AccessMapper access,
            PermissionChecker checker,
            AuditLog audit,
            Clock clock,
            ObjectMapper json) {
        this.catalog = catalog;
        this.access = access;
        this.checker = checker;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    public static List<String> implementedCodes() {
        List<String> codes = new ArrayList<>();
        for (String app : List.of("oa", "pmis"))
            for (String action :
                    List.of(
                            "view",
                            "create",
                            "update",
                            "submit",
                            "withdraw",
                            "approve",
                            "reject",
                            app.equals("oa") ? "void" : "cancel"))
                codes.add(app + ":" + (app.equals("oa") ? "document" : "project") + ":" + action);
        codes.add(OP);
        return List.copyOf(codes);
    }

    private PermissionContext context(Actor a, String op) {
        return new PermissionContext(a.id(), a.sessionId(), "admin", op, Map.of());
    }

    private void identity(Actor a) {
        var u = access.user(a.id());
        if (u == null
                || !u.enabled()
                || !u.employeeEnabled()
                || !u.organizationEnabled()
                || u.authVersion() != a.authVersion()
                || a.sessionId() == null) throw failure(401, "IDENTITY_REVOKED", "身份已变化，请重新登录");
    }

    private void platform(Actor a) {
        identity(a);
        if (!checker.allowsAll(context(a, OP), Set.of(OP, "admin:platform:manage")))
            throw failure(403, "PLATFORM_REQUIRED", "仅平台目录管理员可执行");
    }

    private long lock(Actor a, long app) {
        long tree = access.lockTree();
        if (app <= 1) {
            access.lockNamespace(1);
            access.lockLastAdmin();
        } else {
            access.lockPlatform();
            if (app > 0) access.lockNamespace(app);
        }
        access.lockUser(a.id());
        platform(a);
        return tree;
    }

    private Application app(long id) {
        var a = catalog.application(id);
        if (a == null) throw failure(404, "APPLICATION_NOT_FOUND", "应用不存在");
        return a;
    }

    private Resource resource(long app, long id) {
        return catalog.resources(app).stream()
                .filter(r -> id(r.id()) == id)
                .findFirst()
                .orElseThrow(() -> failure(404, "RESOURCE_NOT_FOUND", "应用资源不存在"));
    }

    private Permission permission(long app, long id) {
        return catalog.permissions(app).stream()
                .filter(p -> id(p.id()) == id)
                .findFirst()
                .orElseThrow(() -> failure(404, "PERMISSION_NOT_FOUND", "应用权限不存在"));
    }

    private void changed(
            Actor a,
            long app,
            String action,
            String kind,
            long target,
            Map<String, ?> before,
            Map<String, ?> after) {
        access.bumpNamespace(app);
        audit.append(a.id(), a.traceId(), action, kind, target, before, after);
    }

    private Map<String, Object> values(Object... pairs) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) v.put((String) pairs[i], pairs[i + 1]);
        return v;
    }

    public List<Application> applications(Actor a) {
        platform(a);
        return catalog.applications();
    }

    public Snapshot snapshot(Actor a, long app) {
        identity(a);
        Application application = app(app);
        if (!checker.allowsAll(context(a, OP), Set.of(OP, "admin:platform:manage"))
                && (!application.enabled()
                        || !checker.allowsManaged(context(a, "admin:role:view"), app)))
            throw failure(403, "APPLICATION_MANAGEMENT_DENIED", "无此应用的目录查询资格");
        return new Snapshot(
                application,
                catalog.resources(app),
                catalog.permissions(app),
                implementedCodes().stream()
                        .filter(c -> c.startsWith(application.code() + ":"))
                        .toList());
    }

    @Transactional
    public Application createApplication(Actor a, ApplicationCreate r) {
        lock(a, 0);
        String code = code(r.code());
        if (code.length() > 32) throw failure(400, "INVALID_CODE", "应用编码最多32位");
        var v = values("code", code, "name", text(r.name(), 100), "description", r.description());
        catalog.createApplication(v);
        long id = ((Number) v.get("id")).longValue();
        catalog.namespace(id);
        changed(a, 1, "APPLICATION_CREATE", "application", id, null, Map.of("applicationId", id));
        return app(id);
    }

    @Transactional
    public Application updateApplication(Actor a, long app, ApplicationUpdate r) {
        lock(a, app);
        var old = app(app);
        version(old.version(), r.expectedVersion());
        catalog.updateApplication(app, text(r.name(), 100), r.description());
        changed(
                a,
                app,
                "APPLICATION_UPDATE",
                "application",
                app,
                Map.of("version", old.version()),
                Map.of("version", old.version() + 1));
        return app(app);
    }

    private void parent(long app, String parent) {
        if (parent != null && !resource(app, id(parent)).kind().equals("DIRECTORY"))
            throw failure(422, "RESOURCE_PARENT_TYPE", "父节点必须为目录");
    }

    /** 每次结构变更在命名空间锁内验证完整树，再批量重建闭包；没有部分移动或跨应用路径。 */
    private void rebuild(long app) {
        Map<String, Resource> nodes = new LinkedHashMap<>();
        for (var n : catalog.resources(app)) nodes.put(n.id(), n);
        List<Map<String, Object>> paths = new ArrayList<>();
        for (var node : nodes.values()) {
            Set<String> seen = new HashSet<>();
            Resource current = node;
            int depth = 0;
            while (current != null) {
                if (!seen.add(current.id())) throw failure(422, "RESOURCE_CYCLE", "资源树不能形成环");
                if (depth > 7) throw failure(422, "RESOURCE_DEPTH", "资源树最多8层");
                paths.add(
                        values(
                                "app",
                                app,
                                "ancestor",
                                id(current.id()),
                                "descendant",
                                id(node.id()),
                                "depth",
                                depth++));
                if (current.parentId() == null) break;
                current = nodes.get(current.parentId());
                if (current == null || !current.kind().equals("DIRECTORY"))
                    throw failure(422, "RESOURCE_PARENT_TYPE", "父节点必须为同应用目录");
            }
        }
        catalog.clearPaths(app);
        if (!paths.isEmpty()) catalog.paths(paths);
    }

    @Transactional
    public Resource createResource(Actor a, long app, ResourceCreate r) {
        lock(a, app);
        app(app);
        parent(app, r.parentId());
        if (!Set.of("DIRECTORY", "FUNCTION").contains(r.kind()))
            throw failure(422, "RESOURCE_KIND", "资源类型无效");
        var v =
                values(
                        "app",
                        app,
                        "parent",
                        r.parentId() == null ? null : id(r.parentId()),
                        "code",
                        code(r.code()),
                        "name",
                        text(r.name(), 100),
                        "kind",
                        r.kind(),
                        "description",
                        r.description());
        catalog.createResource(v);
        long id = ((Number) v.get("id")).longValue();
        rebuild(app);
        changed(a, app, "RESOURCE_CREATE", "resource", id, null, Map.of("applicationId", app));
        return resource(app, id);
    }

    @Transactional
    public Resource updateResource(Actor a, long app, long target, ResourceUpdate r) {
        lock(a, app);
        var old = resource(app, target);
        version(old.version(), r.expectedVersion());
        parent(app, r.parentId());
        // 移动不能绕过启停预览：受引用节点须先显式停用，再移动，最后预览启用。
        boolean oldActive = old.enabled() && catalog.disabledAncestors(app, target) == 0;
        boolean newActive =
                old.enabled()
                        && (r.parentId() == null
                                || catalog.disabledAncestors(app, id(r.parentId())) == 0);
        if (oldActive != newActive && catalog.impact(app, "RESOURCE", target).roles() > 0)
            throw failure(409, "RESOURCE_ACTIVATION_CHANGE", "移动会改变被引用权限的启用状态，请先预览停用该节点，移动后再预览启用");
        catalog.updateResource(
                target,
                r.parentId() == null ? null : id(r.parentId()),
                text(r.name(), 100),
                r.description());
        rebuild(app);
        changed(
                a,
                app,
                "RESOURCE_UPDATE",
                "resource",
                target,
                Map.of("version", old.version()),
                Map.of("version", old.version() + 1));
        return resource(app, target);
    }

    @Transactional
    public Permission createPermission(Actor a, long app, PermissionCreate r) {
        lock(a, app);
        Application application = app(app);
        Resource resource = resource(app, id(r.resourceId()));
        if (!resource.kind().equals("FUNCTION"))
            throw failure(422, "PERMISSION_RESOURCE_TYPE", "权限点必须绑定功能节点");
        String code = application.code() + ":" + resource.code() + ":" + r.action();
        if (code.length() > 128) throw failure(400, "INVALID_CODE", "权限编码过长");
        var v =
                values(
                        "app",
                        app,
                        "resource",
                        id(resource.id()),
                        "code",
                        code,
                        "action",
                        r.action(),
                        "name",
                        text(r.name(), 100),
                        "description",
                        r.description(),
                        "implementation",
                        implementedCodes().contains(code) ? "IMPLEMENTED" : "PLANNED");
        catalog.createPermission(v);
        long id = ((Number) v.get("id")).longValue();
        changed(a, app, "PERMISSION_CREATE", "permission", id, null, Map.of("code", code));
        return permission(app, id);
    }

    @Transactional
    public Permission updatePermission(Actor a, long app, long target, PermissionUpdate r) {
        lock(a, app);
        var old = permission(app, target);
        version(old.version(), r.expectedVersion());
        catalog.updatePermission(target, text(r.name(), 100), r.description());
        changed(
                a,
                app,
                "PERMISSION_UPDATE",
                "permission",
                target,
                Map.of("version", old.version()),
                Map.of("version", old.version() + 1));
        return permission(app, target);
    }

    private long targetVersion(long app, String kind, long target) {
        return switch (kind) {
            case "APPLICATION" -> {
                if (app != target) throw failure(404, "APPLICATION_NOT_FOUND", "应用不匹配");
                yield app(app).version();
            }
            case "RESOURCE" -> resource(app, target).version();
            case "PERMISSION" -> permission(app, target).version();
            default -> throw failure(400, "CATALOG_TARGET", "目录目标无效");
        };
    }

    @Transactional
    public StatusPreview preview(Actor a, long app, String kind, long target, Status r) {
        long tree = lock(a, app);
        if (app == 1) throw failure(422, "PLATFORM_CATALOG_PROTECTED", "平台管理根目录与权限不允许启停");
        long v = targetVersion(app, kind, target);
        version(v, r.expectedVersion());
        var impact = catalog.impact(app, kind, target);
        var p =
                new StatusPreview(
                        UUID.randomUUID().toString(),
                        kind,
                        Long.toString(target),
                        Long.toString(app),
                        v,
                        access.namespace(app),
                        tree,
                        r.enabled(),
                        impact.roles(),
                        impact.users(),
                        clock.instant().plus(Duration.ofMinutes(15)));
        try {
            access.preview(
                    new AccessRows.PreviewRow(
                            p.token(),
                            a.id(),
                            "CATALOG_" + kind,
                            target,
                            app,
                            v,
                            tree,
                            "{}",
                            json.writeValueAsString(p),
                            p.expiresAt(),
                            false));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Preview encoding failed", e);
        }
        return p;
    }

    @Transactional
    public Object commit(Actor a, long app, String kind, long target, Commit r) {
        long tree = lock(a, app);
        if (app == 1) throw failure(422, "PLATFORM_CATALOG_PROTECTED", "平台管理根目录与权限不允许启停");
        var row = access.previewForUpdate(r.previewToken());
        if (row == null
                || row.actorUserId() != a.id()
                || !Objects.equals(row.applicationId(), app)
                || row.targetId() != target
                || !row.kind().equals("CATALOG_" + kind)
                || row.consumed()) throw failure(409, "PREVIEW_CONFLICT", "预览不匹配或已使用");
        if (!clock.instant().isBefore(row.expiresAt()))
            throw failure(410, "PREVIEW_EXPIRED", "预览已过期，请重新预览");
        StatusPreview p;
        try {
            p = json.readValue(row.payloadJson(), StatusPreview.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Preview decoding failed", e);
        }
        if (p.treeVersion() != tree
                || p.namespaceVersion() != access.namespace(app)
                || p.targetVersion() != targetVersion(app, kind, target))
            throw failure(409, "PREVIEW_STALE", "目录或授权已变化，请重新预览");
        switch (kind) {
            case "APPLICATION" -> catalog.applicationStatus(target, p.enabled());
            case "RESOURCE" -> catalog.resourceStatus(target, p.enabled());
            case "PERMISSION" -> catalog.permissionStatus(target, p.enabled());
            default -> throw failure(400, "CATALOG_TARGET", "目录目标无效");
        }
        access.consume(p.token());
        changed(
                a,
                app,
                kind + "_STATUS",
                kind.toLowerCase(Locale.ROOT),
                target,
                Map.of("version", p.targetVersion()),
                Map.of("enabled", p.enabled()));
        return switch (kind) {
            case "APPLICATION" -> app(app);
            case "RESOURCE" -> resource(app, target);
            default -> permission(app, target);
        };
    }
}

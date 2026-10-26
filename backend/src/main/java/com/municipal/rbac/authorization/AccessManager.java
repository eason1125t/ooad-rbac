/** 功能：单应用RBAC0管理规则及事务；HTTP只传服务端Actor，核心不依赖security/HTTP/OA/PMIS。 */
package com.municipal.rbac.authorization;

import static com.municipal.rbac.authorization.AccessData.*;
import static com.municipal.rbac.authorization.internal.AccessRows.*;
import static com.municipal.rbac.identity.IdentityData.code;
import static com.municipal.rbac.identity.IdentityData.failure;
import static com.municipal.rbac.identity.IdentityData.id;
import static com.municipal.rbac.identity.IdentityData.nameKey;
import static com.municipal.rbac.identity.IdentityData.offset;
import static com.municipal.rbac.identity.IdentityData.search;
import static com.municipal.rbac.identity.IdentityData.text;
import static com.municipal.rbac.identity.IdentityData.version;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.municipal.rbac.audit.AuditLog;
import com.municipal.rbac.authorization.internal.AccessMapper;
import com.municipal.rbac.authorization.internal.GrantMapper;
import com.municipal.rbac.shared.DomainFailure;
import com.municipal.rbac.shared.PageResult;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class AccessManager {
    private final AccessMapper data;
    private final GrantMapper sources;
    private final PermissionChecker checker;
    private final Clock clock;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final boolean demo;

    public AccessManager(
            AccessMapper data,
            GrantMapper sources,
            PermissionChecker checker,
            Clock clock,
            ObjectMapper json,
            AuditLog audit,
            Environment environment) {
        this.data = data;
        this.sources = sources;
        this.checker = checker;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.demo = environment.acceptsProfiles(Profiles.of("demo"));
    }

    private String sid(long id) {
        return Long.toString(id);
    }

    private PermissionContext context(Actor a, String op) {
        return new PermissionContext(a.id(), a.sessionId(), "admin", op, Map.of());
    }

    private boolean root(Actor a, String op) {
        return checker.allowsAll(context(a, op), Set.of(op, "admin:platform:manage"));
    }

    private void actor(Actor a) {
        User u = user(a.id());
        if (!u.enabled()
                || !u.employeeEnabled()
                || !u.organizationEnabled()
                || u.authVersion() != a.authVersion()
                || a.sessionId() == null) throw failure(401, "IDENTITY_REVOKED", "身份已变化，请重新登录");
    }

    private void allowed(Actor a, long app, String op) {
        actor(a);
        application(app);
        if (!checker.allowsManaged(context(a, op), app))
            throw failure(403, "APPLICATION_MANAGEMENT_DENIED", "无此应用的管理资格");
    }

    private void platform(Actor a, long app, String op) {
        allowed(a, app, op);
        if (!root(a, op)) throw failure(403, "PLATFORM_REQUIRED", "仅平台管理员可执行");
    }

    private App application(long id) {
        return data.apps().stream()
                .filter(a -> a.id() == id && a.enabled())
                .findFirst()
                .orElseThrow(() -> failure(404, "APPLICATION_NOT_FOUND", "应用不存在或已停用"));
    }

    private User user(long id) {
        User u = data.user(id);
        if (u == null) throw failure(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        return u;
    }

    private void eligible(User u) {
        if (!u.enabled()
                || !u.employeeEnabled()
                || !u.organizationEnabled()
                || (!demo && u.employeeId() == null))
            throw failure(422, "PERSON_UNAVAILABLE", "人员账号或组织不可用");
    }

    private long lock(Actor a, long app, String op, Collection<Long> targets) {
        long tree = data.lockTree();
        if (app == 1) {
            data.lockNamespace(1);
            data.lockLastAdmin();
        } else {
            data.lockPlatform();
            data.lockNamespace(app);
        }
        TreeSet<Long> ids = new TreeSet<>(targets);
        ids.add(a.id());
        for (long id : ids)
            if (data.lockUser(id) == null) throw failure(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        allowed(a, app, op);
        return tree;
    }

    private long lock(Actor a, long app, String op) {
        return lock(a, app, op, List.of());
    }

    private boolean within(Instant from, Instant to) {
        return within(clock.instant(), from, to);
    }

    private boolean within(Instant n, Instant from, Instant to) {
        return (from == null || !n.isBefore(from)) && (to == null || n.isBefore(to));
    }

    private void times(Instant from, Instant to) {
        if ((from != null && from.getNano() % 1000000 != 0)
                || (to != null && to.getNano() % 1000000 != 0)
                || from != null && to != null && !from.isBefore(to))
            throw failure(422, "INVALID_VALIDITY", "有效期须为毫秒精度且开始早于结束");
    }

    private boolean extendsTime(Instant oldFrom, Instant oldTo, Instant from, Instant to) {
        return oldFrom != null && (from == null || from.isBefore(oldFrom))
                || oldTo != null && (to == null || to.isAfter(oldTo));
    }

    private boolean ancestor(String root, String node) {
        return data.ancestor(id(root), id(node)) > 0;
    }

    private void scope(Scope s) {
        if (s == null
                || s.organizationIds() == null
                || s.organizationIds().size() > 100
                || !Set.of("ALL", "SELF", "ORG", "ORG_SUBTREE").contains(s.kind()))
            throw failure(422, "INVALID_SCOPE", "数据范围模板无效");
        unique(s.organizationIds(), Function.identity());
        boolean nodes = s.kind().startsWith("ORG");
        if (nodes == s.organizationIds().isEmpty())
            throw failure(422, "INVALID_SCOPE", "指定组织范围须有节点，其余模板不接受节点");
        for (String n : s.organizationIds())
            if (data.activeOrg(id(n)) != 1)
                throw failure(422, "ORGANIZATION_DISABLED", "范围节点不存在或未启用");
    }

    private void people(PeopleScope s) {
        if (s == null
                || s.allCompany() == null
                || s.organizations() == null
                || s.organizations().size() > 100)
            throw failure(422, "INVALID_PEOPLE_SCOPE", "人员范围无效");
        unique(s.organizations(), OrganizationRule::organizationId);
        if (s.allCompany() && !s.organizations().isEmpty()
                || !s.allCompany() && s.organizations().isEmpty())
            throw failure(422, "INVALID_PEOPLE_SCOPE", "全公司不带节点，指定组织至少一个节点");
        for (var n : s.organizations())
            if (n.includeDescendants() == null || data.activeOrg(id(n.organizationId())) != 1)
                throw failure(422, "ORGANIZATION_DISABLED", "范围节点不存在或未启用");
    }

    private <T> void unique(List<T> list, Function<T, String> key) {
        if (list == null || list.stream().map(key).distinct().count() != list.size())
            throw failure(422, "DUPLICATE_ITEM", "列表包含重复项");
    }

    private PeopleScope people(boolean all, List<Node> nodes) {
        return new PeopleScope(
                all,
                nodes.stream()
                        .map(n -> new OrganizationRule(n.organizationId(), n.includeDescendants()))
                        .toList());
    }

    private boolean contains(PeopleScope s, Long org) {
        return s.allCompany()
                || org != null
                        && s.organizations().stream()
                                .anyMatch(
                                        n ->
                                                n.includeDescendants()
                                                        ? ancestor(n.organizationId(), sid(org))
                                                        : id(n.organizationId()) == org);
    }

    private boolean coversPeople(PeopleScope ceiling, PeopleScope requested) {
        if (ceiling.allCompany()) return true;
        if (requested.allCompany()) return false;
        return requested.organizations().stream()
                .allMatch(
                        n ->
                                ceiling.organizations().stream()
                                        .anyMatch(
                                                c ->
                                                        c.includeDescendants()
                                                                ? ancestor(
                                                                        c.organizationId(),
                                                                        n.organizationId())
                                                                : !n.includeDescendants()
                                                                        && c.organizationId()
                                                                                .equals(
                                                                                        n
                                                                                                .organizationId())));
    }

    private RoleRow role(long app, long id) {
        RoleRow r = data.role(id);
        if (r == null || !Objects.equals(r.applicationId(), app))
            throw failure(404, "ROLE_NOT_FOUND", "应用角色不存在");
        return r;
    }

    private PeopleScope rolePeople(RoleRow r) {
        return people(r.personnelAll(), data.roleNodes(r.id()));
    }

    private DelegateRow activeDelegation(long app, long user) {
        DelegateRow d = data.ownDelegation(app, user);
        if (d == null || !d.enabled() || !within(d.validFrom(), d.validTo()))
            throw failure(403, "DELEGATION_INACTIVE", "管理委派未生效");
        eligible(user(user));
        return d;
    }

    private PeopleScope delegationPeople(DelegateRow d) {
        return people(d.peopleAll(), data.delegationNodes(d.id()));
    }

    private Delegation delegation(DelegateRow d) {
        return new Delegation(
                sid(d.id()),
                sid(d.applicationId()),
                sid(d.administratorUserId()),
                d.enabled(),
                d.validFrom(),
                d.validTo(),
                delegationPeople(d),
                data.commonRoles(d.id()),
                data.ceilings(d.id()).stream()
                        .map(
                                p ->
                                        new Ceiling(
                                                p.permissionId(),
                                                new Scope(
                                                        p.scopeKind(),
                                                        data.ceilingNodes(
                                                                d.id(), id(p.permissionId())))))
                        .toList(),
                d.version());
    }

    private boolean protectedUser(long user) {
        return data.potentialDelegate(user, clock.instant()) > 0
                || data.potentialPlatform(user, clock.instant()) > 0;
    }

    private void visible(Actor a, long app, RoleRow r, String op) {
        if (root(a, op)) return;
        DelegateRow d = activeDelegation(app, a.id());
        if (!(r.kind().equals("SCOPED") && Objects.equals(r.ownerUserId(), a.id())
                || r.kind().equals("COMMON") && data.commonRoles(d.id()).contains(sid(r.id()))))
            throw failure(403, "ROLE_SCOPE_DENIED", "角色不在可管理范围内");
    }

    private void maintainPerson(Actor a, long app, long target, String op, boolean modify) {
        user(target);
        if (root(a, op)) return;
        DelegateRow d = activeDelegation(app, a.id());
        Responsibility r = data.responsibility(app, target);
        if (r == null
                || !r.state().equals("CURRENT")
                || !Objects.equals(r.delegationId(), sid(d.id()))
                || !contains(delegationPeople(d), user(target).organizationId()))
            throw failure(403, "PERSON_SCOPE_DENIED", "人员不在当前维护责任及组织范围内");
        if (modify && (target == a.id() || protectedUser(target)))
            throw failure(403, "ADMINISTRATOR_TARGET_DENIED", "业务管理员不能修改自己或其他管理员的业务角色");
    }

    private void editable(Actor a, long app, RoleRow r, String op) {
        visible(a, app, r, op);
        if (root(a, op)) return;
        if (!r.kind().equals("SCOPED") || !Objects.equals(r.ownerUserId(), a.id()))
            throw failure(403, "ROLE_READ_ONLY", "业务管理员仅可维护本人专用角色");
        if (!coversPeople(delegationPeople(activeDelegation(app, a.id())), rolePeople(r)))
            throw failure(403, "ROLE_PERSONNEL_SCOPE_DENIED", "角色完整人员范围超出管理范围");
        for (long u : data.memberIds(r.id())) maintainPerson(a, app, u, op, true);
    }

    private Role view(Actor a, long app, RoleRow r, String op) {
        boolean edit = true, assign = true;
        try {
            editable(a, app, r, "admin:role:update");
        } catch (DomainFailure denied) {
            edit = false;
        }
        try {
            visible(a, app, r, "admin:grant:update");
            if (!r.enabled()) assign = false;
            else fullCeiling(a, app, r, "admin:grant:update");
        } catch (DomainFailure denied) {
            assign = false;
        }
        return new Role(
                sid(r.id()),
                sid(app),
                r.code(),
                r.name(),
                r.kind(),
                r.ownerUserId() == null ? null : sid(r.ownerUserId()),
                r.enabled(),
                r.version(),
                rolePeople(r),
                edit,
                assign,
                r.memberCount());
    }

    private Map<String, Object> filter(Actor a, long app, String op, int page, int size, String q) {
        allowed(a, app, op);
        Map<String, Object> m = new HashMap<>();
        boolean root = root(a, op);
        DelegateRow d = root ? null : activeDelegation(app, a.id());
        m.put("app", app);
        m.put("root", root);
        m.put("actor", a.id());
        m.put("delegation", d == null ? 0 : d.id());
        m.put("all", d != null && d.peopleAll());
        m.put("offset", offset(page, size));
        m.put("size", size);
        m.put("q", search(q));
        return m;
    }

    public List<Permission> permissions(Actor a, long app) {
        allowed(a, app, "admin:role:view");
        return data.permissions(app);
    }

    /** 只返回服务端允许的组织元数据及其祖先；不把全公司目录交给浏览器再隐藏。 */
    public List<OrganizationChoice> organizations(Actor a, long app) {
        allowed(a, app, "admin:role:view");
        boolean platform = root(a, "admin:role:view");
        var rows = data.organizationDirectory();
        var byId =
                rows.stream()
                        .collect(
                                Collectors.toMap(
                                        com.municipal.rbac.identity.IdentityData.Organization::id,
                                        Function.identity()));
        java.util.function.BiPredicate<String, String> ancestry =
                (parent, child) -> {
                    String cursor = child;
                    for (int depth = 0; cursor != null && depth < 8; depth++) {
                        if (parent.equals(cursor)) return true;
                        var node = byId.get(cursor);
                        cursor = node == null ? null : node.parentId();
                    }
                    return false;
                };
        PeopleScope personnel =
                platform
                        ? new PeopleScope(true, List.of())
                        : delegationPeople(activeDelegation(app, a.id()));
        List<Capabilities> grants = new ArrayList<>();
        if (!platform) {
            var d = activeDelegation(app, a.id());
            for (Ceiling c : delegation(d).permissionCeilings())
                grants.add(
                        ScopePolicy.intersect(
                                ownCapabilities(a.id(), app, c.permissionId()),
                                ScopePolicy.union(List.of(c.scope())),
                                ancestry));
        }
        Set<String> visible = new HashSet<>();
        Map<String, Boolean> selectable = new HashMap<>(), subtrees = new HashMap<>();
        for (var node : rows) {
            boolean person =
                    personnel.allCompany()
                            || personnel.organizations().stream()
                                    .anyMatch(
                                            rule ->
                                                    rule.includeDescendants()
                                                            ? ancestry.test(
                                                                    rule.organizationId(),
                                                                    node.id())
                                                            : rule.organizationId()
                                                                    .equals(node.id()));
            boolean subtree =
                    personnel.allCompany()
                            || personnel.organizations().stream()
                                    .anyMatch(
                                            rule ->
                                                    rule.includeDescendants()
                                                            && ancestry.test(
                                                                    rule.organizationId(),
                                                                    node.id()));
            selectable.put(node.id(), person);
            subtrees.put(node.id(), subtree);
            if (person
                    || grants.stream()
                            .anyMatch(
                                    c ->
                                            ScopePolicy.covers(
                                                    c,
                                                    new Scope("ORG", List.of(node.id())),
                                                    ancestry))) visible.add(node.id());
        }
        for (String n : List.copyOf(visible)) {
            var node = byId.get(n);
            for (int depth = 0; node != null && node.parentId() != null && depth < 8; depth++) {
                visible.add(node.parentId());
                node = byId.get(node.parentId());
            }
        }
        return rows.stream()
                .filter(n -> visible.contains(n.id()))
                .map(
                        n -> {
                            boolean enabled = n.enabled();
                            var parent = byId.get(n.parentId());
                            for (int depth = 0; parent != null && depth < 8; depth++) {
                                enabled &= parent.enabled();
                                parent = byId.get(parent.parentId());
                            }
                            return new OrganizationChoice(
                                    n.id(),
                                    n.code(),
                                    n.name(),
                                    n.parentId(),
                                    n.nodeType(),
                                    enabled,
                                    enabled && selectable.get(n.id()),
                                    enabled && subtrees.get(n.id()));
                        })
                .toList();
    }

    public PageResult<Role> roles(Actor a, long app, int page, int size, String q) {
        var f = filter(a, app, "admin:role:view", page, size, q);
        return new PageResult<>(
                data.roles(f).stream().map(r -> view(a, app, r, "admin:role:view")).toList(),
                page,
                size,
                data.roleCount(f));
    }

    public Role role(Actor a, long app, long target) {
        allowed(a, app, "admin:role:view");
        RoleRow r = role(app, target);
        visible(a, app, r, "admin:role:view");
        return view(a, app, r, "admin:role:view");
    }

    private void writeRolePeople(long role, PeopleScope scope) {
        data.clearRoleNodes(role);
        for (var n : scope.organizations())
            data.roleNode(role, id(n.organizationId()), n.includeDescendants());
    }

    private void owner(long app, long user, PeopleScope scope) {
        DelegateRow d = activeDelegation(app, user);
        if (!coversPeople(delegationPeople(d), scope))
            throw failure(422, "OWNER_SCOPE_DENIED", "维护人的组织范围不能覆盖角色适用范围");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Role createRole(Actor a, long app, RoleCreate r) {
        lock(
                a,
                app,
                "admin:role:create",
                r.ownerUserId() == null ? List.of() : List.of(id(r.ownerUserId())));
        people(r.personnelScope());
        if (!Set.of("PLATFORM", "COMMON", "SCOPED").contains(r.kind())
                || app == 1 != r.kind().equals("PLATFORM")
                || r.kind().equals("SCOPED") != (r.ownerUserId() != null))
            throw failure(422, "INVALID_ROLE_KIND", "角色类型、应用或维护人不一致");
        if (!root(a, "admin:role:create")
                && (!r.kind().equals("SCOPED") || id(r.ownerUserId()) != a.id()))
            throw failure(403, "ROLE_CREATE_DENIED", "业务管理员只能创建本人维护的专用角色");
        if (r.kind().equals("SCOPED")) owner(app, id(r.ownerUserId()), r.personnelScope());
        else if (!r.personnelScope().allCompany())
            throw failure(422, "COMMON_PERSONNEL_SCOPE", "通用/平台角色人员适用范围须全公司");
        Map<String, Object> v = new HashMap<>();
        v.put("app", app);
        v.put("code", code(r.code()));
        v.put("name", text(r.name(), 100));
        v.put("nameKey", nameKey(r.name()));
        v.put("kind", r.kind());
        v.put("owner", r.ownerUserId() == null ? null : id(r.ownerUserId()));
        v.put("all", r.personnelScope().allCompany());
        data.insertRole(v);
        long id = ((Number) v.get("id")).longValue();
        writeRolePeople(id, r.personnelScope());
        changed(a, app, "ROLE_CREATE", id, Map.of(), Map.of("kind", r.kind()));
        return view(a, app, role(app, id), "admin:role:create");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Role updateRole(Actor a, long app, long target, RoleUpdate request) {
        lock(a, app, "admin:role:update");
        RoleRow r = role(app, target);
        editable(a, app, r, "admin:role:update");
        version(r.version(), request.expectedVersion());
        people(request.personnelScope());
        if (r.kind().equals("SCOPED")) owner(app, r.ownerUserId(), request.personnelScope());
        else if (!request.personnelScope().allCompany())
            throw failure(422, "COMMON_PERSONNEL_SCOPE", "通用角色人员范围须全公司");
        for (long u : data.memberIds(target))
            if (!contains(request.personnelScope(), user(u).organizationId()))
                throw failure(409, "ROLE_MEMBERS_OUTSIDE_SCOPE", "先处理不适用的新范围成员");
        Map<String, Object> m =
                Map.of(
                        "id",
                        target,
                        "name",
                        text(request.name(), 100),
                        "nameKey",
                        nameKey(request.name()),
                        "all",
                        request.personnelScope().allCompany());
        data.updateRole(m);
        writeRolePeople(target, request.personnelScope());
        changed(
                a,
                app,
                "ROLE_UPDATE",
                target,
                Map.of("version", r.version()),
                Map.of("personnelScope", request.personnelScope()));
        return view(a, app, role(app, target), "admin:role:update");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Role roleStatus(Actor a, long app, long target, Status request) {
        lock(a, app, "admin:role:status");
        RoleRow r = role(app, target);
        editable(a, app, r, "admin:role:status");
        version(r.version(), request.expectedVersion());
        if (r.enabled() == request.enabled()) return view(a, app, r, "admin:role:status");
        if (request.enabled()) fullCeiling(a, app, r, "admin:role:status");
        data.roleStatus(target, request.enabled());
        lastAdmin();
        changed(
                a,
                app,
                "ROLE_STATUS",
                target,
                Map.of("enabled", r.enabled()),
                Map.of("enabled", request.enabled()));
        return view(a, app, role(app, target), "admin:role:status");
    }

    public RolePermissions rolePermissions(Actor a, long app, long target) {
        allowed(a, app, "admin:role:view");
        RoleRow r = role(app, target);
        visible(a, app, r, "admin:role:view");
        return permissionView(app, r);
    }

    private RolePermissions permissionView(long app, RoleRow role) {
        Map<String, Permission> p =
                data.permissions(app).stream()
                        .collect(Collectors.toMap(Permission::id, Function.identity()));
        return new RolePermissions(
                sid(role.id()),
                role.version(),
                definition(role.id()).stream()
                        .map(
                                g ->
                                        new PermissionView(
                                                p.get(g.permissionId()),
                                                g.scope(),
                                                g.validFrom(),
                                                g.validTo()))
                        .toList());
    }

    private List<PermissionGrant> definition(long role) {
        return data.rolePermissions(role).stream()
                .map(
                        p ->
                                new PermissionGrant(
                                        p.permissionId(),
                                        new Scope(
                                                p.scopeKind(),
                                                data.permissionNodes(role, id(p.permissionId()))),
                                        p.validFrom(),
                                        p.validTo()))
                .toList();
    }

    private Capabilities ownCapabilities(long actor, long app, String permission) {
        return effective(actor, app).permissions().stream()
                .filter(p -> p.permissionId().equals(permission))
                .findFirst()
                .map(EffectivePermission::capabilities)
                .orElse(ScopePolicy.empty());
    }

    private void ceiling(Actor a, long app, String permission, Scope requested, String op) {
        if (root(a, op)) return;
        DelegateRow d = activeDelegation(app, a.id());
        PermissionRow configured =
                data.ceilings(d.id()).stream()
                        .filter(p -> p.permissionId().equals(permission))
                        .findFirst()
                        .orElseThrow(
                                () -> failure(403, "DELEGATION_CEILING_EXCEEDED", "超出平台配置的权限上限"));
        Scope maximum =
                new Scope(configured.scopeKind(), data.ceilingNodes(d.id(), id(permission)));
        if (!ScopePolicy.covers(ScopePolicy.union(List.of(maximum)), requested, this::ancestor)
                || !ScopePolicy.covers(
                        ownCapabilities(a.id(), app, permission), requested, this::ancestor))
            throw failure(403, "CURRENT_PERMISSION_CEILING_EXCEEDED", "新增或扩大授权超出当前本人权限与平台上限交集");
    }

    private void fullCeiling(Actor a, long app, RoleRow role, String op) {
        for (var p : definition(role.id())) {
            validPermission(app, p.permissionId());
            ceiling(a, app, p.permissionId(), p.scope(), op);
        }
    }

    private void validPermission(long app, String id) {
        Permission p =
                data.permissions(app).stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow(
                                () -> failure(422, "CROSS_APPLICATION_PERMISSION", "权限不存在或属于其他应用"));
        if (!p.enabled() || !p.implementationStatus().equals("IMPLEMENTED"))
            throw failure(422, "PERMISSION_NOT_IMPLEMENTED", "停用或未实现的权限不可新授予");
    }

    private void validatePermissions(Actor a, long app, RoleRow role, PermissionSet request) {
        editable(a, app, role, "admin:role:permissions");
        version(role.version(), request.expectedVersion());
        unique(request.permissions(), PermissionGrant::permissionId);
        Map<String, PermissionGrant> old =
                definition(role.id()).stream()
                        .collect(
                                Collectors.toMap(
                                        PermissionGrant::permissionId, Function.identity()));
        for (PermissionGrant p : request.permissions()) {
            id(p.permissionId());
            scope(p.scope());
            times(p.validFrom(), p.validTo());
            PermissionGrant before = old.get(p.permissionId());
            List<Scope> additions =
                    before == null
                                    || extendsTime(
                                            before.validFrom(),
                                            before.validTo(),
                                            p.validFrom(),
                                            p.validTo())
                            ? List.of(p.scope())
                            : ScopePolicy.additions(before.scope(), p.scope(), this::ancestor);
            for (Scope delta : additions) {
                validPermission(app, p.permissionId());
                ceiling(a, app, p.permissionId(), delta, "admin:role:permissions");
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview previewPermissions(Actor a, long app, long target, PermissionSet request) {
        long tree = lock(a, app, "admin:role:permissions");
        RoleRow r = role(app, target);
        validatePermissions(a, app, r, request);
        return preview(
                a,
                "ROLE_PERMISSIONS",
                target,
                app,
                r.version(),
                tree,
                request,
                r.memberCount(),
                Map.of("permissions", definition(target)),
                Map.of("permissions", request.permissions()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RolePermissions replacePermissions(Actor a, long app, long target, Commit request) {
        long tree = lock(a, app, "admin:role:permissions");
        PreviewRow p = load(a, "ROLE_PERMISSIONS", target, app, request.previewToken(), tree);
        PermissionSet r = decode(p.payloadJson(), PermissionSet.class);
        RoleRow old = role(app, target);
        validatePermissions(a, app, old, r);
        var before = definition(target);
        data.clearPermissionNodes(target);
        data.clearPermissions(target);
        for (var g : r.permissions()) {
            data.rolePermission(
                    target, id(g.permissionId()), g.scope().kind(), g.validFrom(), g.validTo());
            for (String n : g.scope().organizationIds())
                data.permissionNode(target, id(g.permissionId()), id(n));
        }
        data.bumpRole(target);
        lastAdmin();
        consume(p);
        changed(
                a,
                app,
                "ROLE_PERMISSIONS",
                target,
                Map.of("permissions", before),
                Map.of("permissions", r.permissions()));
        return permissionView(app, role(app, target));
    }

    public Effective effective(long subject, long app) {
        application(app);
        user(subject);
        List<EffectiveSource> result = new ArrayList<>();
        Map<String, List<Scope>> scopes = new LinkedHashMap<>();
        Map<String, String> codes = new HashMap<>();
        Instant now = clock.instant();
        for (Link s : sources.scopeSources(subject, app))
            if (within(now, s.userFrom(), s.userTo()) && within(now, s.roleFrom(), s.roleTo())) {
                Scope range =
                        new Scope(
                                s.scopeKind(),
                                data.permissionNodes(id(s.roleId()), id(s.permissionId())));
                result.add(
                        new EffectiveSource(
                                s.roleId(),
                                s.permissionId(),
                                s.code(),
                                range,
                                later(s.userFrom(), s.roleFrom()),
                                earlier(s.userTo(), s.roleTo()),
                                s.grantedByUserId(),
                                s.legacy()));
                // 历史跨应用引导来源仅诊断，不作为普通业务数据范围或委派上限。
                if (!s.legacy()) {
                    scopes.computeIfAbsent(s.permissionId(), ignored -> new ArrayList<>())
                            .add(range);
                    codes.put(s.permissionId(), s.code());
                }
            }
        return new Effective(
                sid(subject),
                sid(app),
                scopes.entrySet().stream()
                        .map(
                                e ->
                                        new EffectivePermission(
                                                e.getKey(),
                                                codes.get(e.getKey()),
                                                ScopePolicy.union(e.getValue())))
                        .toList(),
                result);
    }

    private Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private Instant earlier(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    public Effective ownEffective(Actor a, long app) {
        actor(a);
        return effective(a.id(), app);
    }

    public Effective userEffective(Actor a, long app, long target) {
        allowed(a, app, "admin:grant:view");
        maintainPerson(a, app, target, "admin:grant:view", false);
        return effective(target, app);
    }

    public UserRoles userRoles(Actor a, long app, long target) {
        allowed(a, app, "admin:grant:view");
        maintainPerson(a, app, target, "admin:grant:view", false);
        return userRoles(app, target);
    }

    private UserRoles userRoles(long app, long target) {
        return new UserRoles(
                sid(app),
                sid(target),
                data.personVersion(app, target),
                data.memberships(app, target),
                responsibilityView(app, target));
    }

    private Responsibility responsibilityView(long app, long target) {
        Responsibility r = data.responsibility(app, target);
        if (r == null || !r.state().equals("CURRENT")) return r;
        DelegateRow d = data.delegation(id(r.delegationId()));
        User manager = d == null ? null : user(d.administratorUserId());
        boolean active =
                d != null
                        && d.enabled()
                        && within(d.validFrom(), d.validTo())
                        && manager.enabled()
                        && manager.employeeEnabled()
                        && manager.organizationEnabled()
                        && contains(delegationPeople(d), user(target).organizationId());
        // 时间自然流逝不依赖后台任务；只读视图即时表示待接管，不改业务授权或CAS版本。
        return active
                ? r
                : new Responsibility(
                        r.applicationId(), r.userId(), r.delegationId(), "AWAITING", r.version());
    }

    private void assignable(
            Actor a,
            long app,
            long target,
            RoleRow role,
            String op,
            Long prospectiveOrg,
            boolean administrator) {
        visible(a, app, role, op);
        if (!role.enabled()) throw failure(422, "ROLE_DISABLED", "角色未启用");
        eligible(user(target));
        if (!contains(rolePeople(role), prospectiveOrg))
            throw failure(422, "ROLE_PERSONNEL_SCOPE_DENIED", "人员不在角色适用范围");
        if (role.kind().equals("SCOPED")) {
            if (administrator)
                throw failure(422, "ADMINISTRATOR_SCOPED_ROLE_DENIED", "管理员使用平台维护的通用业务角色");
            DelegateRow owner = activeDelegation(app, role.ownerUserId());
            Responsibility r = data.responsibility(app, target);
            if (r == null
                    || !r.state().equals("CURRENT")
                    || !r.delegationId().equals(sid(owner.id()))
                    || !contains(delegationPeople(owner), prospectiveOrg))
                throw failure(422, "SCOPED_RESPONSIBILITY_REQUIRED", "专用角色须与人员当前维护人一致");
        }
        fullCeiling(a, app, role, op);
    }

    private void validateUserRoles(
            Actor a,
            long app,
            long target,
            UserRoleSet request,
            String op,
            Long prospectiveOrg,
            boolean administrator) {
        maintainPerson(a, app, target, op, true);
        version(data.personVersion(app, target), request.expectedVersion());
        unique(request.memberships(), MembershipInput::roleId);
        if (request.memberships().size() > 100) throw failure(422, "TOO_MANY_ROLES", "单应用最多100项角色");
        Map<String, Membership> old =
                data.memberships(app, target).stream()
                        .collect(Collectors.toMap(Membership::roleId, Function.identity()));
        for (var m : request.memberships()) {
            RoleRow r = role(app, id(m.roleId()));
            times(m.validFrom(), m.validTo());
            Membership previous = old.get(m.roleId());
            if (previous == null
                    || extendsTime(
                            previous.validFrom(), previous.validTo(), m.validFrom(), m.validTo()))
                assignable(a, app, target, r, op, prospectiveOrg, administrator);
        }
    }

    private void writeUserRoles(Actor a, long app, long target, List<MembershipInput> requested) {
        Map<String, Membership> old =
                data.memberships(app, target).stream()
                        .collect(Collectors.toMap(Membership::roleId, Function.identity()));
        Set<String> keep =
                requested.stream().map(MembershipInput::roleId).collect(Collectors.toSet());
        for (var before : old.values())
            if (!keep.contains(before.roleId())) data.removeMember(target, id(before.roleId()));
        for (var m : requested) {
            Membership before = old.get(m.roleId());
            if (before == null
                    || !Objects.equals(before.validFrom(), m.validFrom())
                    || !Objects.equals(before.validTo(), m.validTo())) {
                if (before != null
                        && !extendsTime(
                                before.validFrom(), before.validTo(), m.validFrom(), m.validTo())) {
                    data.narrowMember(target, id(m.roleId()), m.validFrom(), m.validTo());
                } else
                    data.member(
                            target,
                            id(m.roleId()),
                            m.validFrom(),
                            m.validTo(),
                            a.id(),
                            clock.instant());
            }
        }
        data.bumpPerson(app, target);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview previewUserRoles(Actor a, long app, long target, UserRoleSet request) {
        long tree = lock(a, app, "admin:grant:update", List.of(target));
        validateUserRoles(
                a,
                app,
                target,
                request,
                "admin:grant:update",
                user(target).organizationId(),
                protectedUser(target));
        return preview(
                a,
                "USER_ROLES",
                target,
                app,
                request.expectedVersion(),
                tree,
                request,
                1,
                Map.of("memberships", data.memberships(app, target)),
                Map.of("memberships", request.memberships()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserRoles replaceUserRoles(Actor a, long app, long target, Commit request) {
        long tree = lock(a, app, "admin:grant:update", List.of(target));
        PreviewRow p = load(a, "USER_ROLES", target, app, request.previewToken(), tree);
        UserRoleSet r = decode(p.payloadJson(), UserRoleSet.class);
        validateUserRoles(
                a,
                app,
                target,
                r,
                "admin:grant:update",
                user(target).organizationId(),
                protectedUser(target));
        var before = data.memberships(app, target);
        writeUserRoles(a, app, target, r.memberships());
        lastAdmin();
        consume(p);
        changed(
                a,
                app,
                "USER_ROLES",
                target,
                Map.of("memberships", before),
                Map.of("memberships", data.memberships(app, target)));
        return userRoles(app, target);
    }

    public PageResult<Person> people(Actor a, long app, int page, int size, String q) {
        var f = filter(a, app, "admin:grant:view", page, size, q);
        return new PageResult<>(
                data.people(f).stream()
                        .map(
                                p ->
                                        new Person(
                                                p.userId(),
                                                p.username(),
                                                p.employeeId(),
                                                p.displayName(),
                                                p.organizationId(),
                                                p.enabled(),
                                                p.grantVersion(),
                                                responsibilityView(app, id(p.userId()))))
                        .toList(),
                page,
                size,
                data.peopleCount(f));
    }

    public PageResult<RoleMember> members(
            Actor a, long app, long target, int page, int size, String q) {
        var f = filter(a, app, "admin:grant:view", page, size, q);
        RoleRow r = role(app, target);
        visible(a, app, r, "admin:grant:view");
        f.put("role", target);
        return new PageResult<>(data.members(f), page, size, data.memberCount(f));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchResult addMembers(Actor a, long app, long target, MemberBatch request) {
        unique(request.members(), MemberInput::userId);
        if (request.members().isEmpty() || request.members().size() > 100)
            throw failure(422, "INVALID_BATCH", "批量须为1至100人");
        lock(
                a,
                app,
                "admin:role:members",
                request.members().stream().map(m -> id(m.userId())).toList());
        RoleRow r = role(app, target);
        visible(a, app, r, "admin:role:members");
        version(r.version(), request.expectedVersion());
        List<UserVersion> result = new ArrayList<>();
        for (var m : request.members()) {
            long u = id(m.userId());
            List<MembershipInput> desired =
                    new ArrayList<>(
                            data.memberships(app, u).stream()
                                    .filter(x -> !x.roleId().equals(sid(target)))
                                    .map(
                                            x ->
                                                    new MembershipInput(
                                                            x.roleId(), x.validFrom(), x.validTo()))
                                    .toList());
            desired.add(new MembershipInput(sid(target), m.validFrom(), m.validTo()));
            UserRoleSet set = new UserRoleSet(m.expectedVersion(), desired);
            validateUserRoles(
                    a,
                    app,
                    u,
                    set,
                    "admin:role:members",
                    user(u).organizationId(),
                    protectedUser(u));
            var before = data.memberships(app, u);
            writeUserRoles(a, app, u, desired);
            audit.append(
                    a.id(),
                    a.traceId(),
                    "ROLE_MEMBER_ADD",
                    "ACCOUNT",
                    u,
                    Map.of("memberships", before),
                    Map.of("memberships", data.memberships(app, u)));
            result.add(new UserVersion(sid(u), data.personVersion(app, u)));
        }
        lastAdmin();
        data.bumpRole(target);
        data.bumpNamespace(app);
        return new BatchResult(sid(target), result.size(), result);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserRoles removeMember(Actor a, long app, long target, MemberRemoval request) {
        long u = id(request.userId());
        lock(a, app, "admin:role:members", List.of(u));
        RoleRow r = role(app, target);
        visible(a, app, r, "admin:role:members");
        maintainPerson(a, app, u, "admin:role:members", true);
        var before = data.memberships(app, u);
        if (before.stream().noneMatch(m -> m.roleId().equals(sid(target))))
            return userRoles(app, u);
        version(r.version(), request.expectedVersion());
        version(data.personVersion(app, u), request.expectedUserVersion());
        data.removeMember(u, target);
        data.bumpPerson(app, u);
        data.bumpRole(target);
        lastAdmin();
        changed(
                a,
                app,
                "ROLE_MEMBER_REMOVE",
                u,
                Map.of("roleId", sid(target)),
                Map.of("removed", true));
        return userRoles(app, u);
    }

    public List<Application> applications(Actor a) {
        actor(a);
        if (!checker.managementConsole(context(a, "admin:application:view")))
            throw failure(403, "MANAGEMENT_CONSOLE_DENIED", "未获得管理资格");
        List<Application> result = new ArrayList<>();
        for (App app : data.apps())
            if (app.enabled()
                    && checker.allowsManaged(context(a, "admin:application:view"), app.id())) {
                boolean platform = root(a, "admin:application:view");
                DelegateRow d = platform ? null : activeDelegation(app.id(), a.id());
                List<DelegablePermission> ceiling = new ArrayList<>();
                if (d != null)
                    for (Ceiling c : delegation(d).permissionCeilings())
                        ceiling.add(
                                new DelegablePermission(
                                        c.permissionId(),
                                        ScopePolicy.intersect(
                                                ownCapabilities(a.id(), app.id(), c.permissionId()),
                                                ScopePolicy.union(List.of(c.scope())),
                                                this::ancestor)));
                result.add(
                        new Application(
                                sid(app.id()),
                                app.code(),
                                app.name(),
                                data.namespace(app.id()),
                                platform,
                                d == null ? null : delegation(d),
                                platform
                                        ? data.permissions(1).stream()
                                                .map(Permission::code)
                                                .toList()
                                        : data.actionsFor(d.id()),
                                ceiling));
            }
        return result;
    }

    public PageResult<Delegation> delegations(Actor a, long app, int page, int size, String q) {
        platform(a, app, "admin:delegation:view");
        var f = filter(a, app, "admin:delegation:view", page, size, q);
        return new PageResult<>(
                data.delegations(f).stream().map(this::delegation).toList(),
                page,
                size,
                data.delegationCount(f));
    }

    private DelegateRow delegation(long app, long id) {
        DelegateRow d = data.delegation(id);
        if (d == null || d.applicationId() != app)
            throw failure(404, "DELEGATION_NOT_FOUND", "委派不存在");
        return d;
    }

    private void validateDelegation(long app, DelegateRow before, DelegationSet r) {
        if (app == 1) throw failure(422, "PLATFORM_DELEGATION_FORBIDDEN", "平台资格通过平台角色管理");
        people(r.peopleScope());
        times(r.validFrom(), r.validTo());
        long user = id(r.administratorUserId());
        if (before == null) {
            if (r.expectedVersion() != null) throw failure(409, "VERSION_CONFLICT", "新增委派版本须显式为空");
        } else {
            if (r.expectedVersion() == null) throw failure(400, "VERSION_REQUIRED", "修改须提供版本");
            version(before.version(), r.expectedVersion());
            if (before.administratorUserId() != user)
                throw failure(422, "DELEGATE_IMMUTABLE", "委派主体不可替换");
        }
        if (r.enabled()) {
            eligible(user(user));
            if (data.scopedMemberships(user, clock.instant()) > 0)
                throw failure(422, "ADMINISTRATOR_SCOPED_ROLE_DENIED", "先撤回管理员现有专用角色，再启用管理委派");
        }
        unique(r.commonRoleIds(), Function.identity());
        unique(r.permissionCeilings(), Ceiling::permissionId);
        for (String role : r.commonRoleIds())
            if (!role(app, id(role)).kind().equals("COMMON"))
                throw failure(422, "COMMON_ROLE_REQUIRED", "白名单仅接受本应用通用角色");
        for (Ceiling c : r.permissionCeilings()) {
            validPermission(app, c.permissionId());
            scope(c.scope());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Delegation saveDelegation(Actor a, long app, Long target, DelegationSet request) {
        lock(a, app, "admin:delegation:manage", List.of(id(request.administratorUserId())));
        platform(a, app, "admin:delegation:manage");
        DelegateRow old = target == null ? null : delegation(app, target);
        validateDelegation(app, old, request);
        // 先固化已经自然失效的责任状态，续期/重新启用不自动取回已待接管人员。
        if (old != null) reconcile(old);
        Map<String, Object> v = new HashMap<>();
        v.put("app", app);
        v.put("user", id(request.administratorUserId()));
        v.put("enabled", request.enabled());
        v.put("from", request.validFrom());
        v.put("to", request.validTo());
        v.put("all", request.peopleScope().allCompany());
        if (old == null) data.insertDelegation(v);
        else {
            v.put("id", target);
            data.updateDelegation(v);
        }
        long id = ((Number) v.get("id")).longValue();
        data.clearCeilingNodes(id);
        data.clearCeilings(id);
        data.clearDelegationNodes(id);
        data.clearCommonRoles(id);
        data.clearActions(id);
        for (var n : request.peopleScope().organizations())
            data.delegationNode(id, id(n.organizationId()), n.includeDescendants());
        for (String r : request.commonRoleIds()) data.commonRole(id, id(r));
        for (Ceiling c : request.permissionCeilings()) {
            data.ceiling(id, id(c.permissionId()), c.scope().kind());
            for (String n : c.scope().organizationIds())
                data.ceilingNode(id, id(c.permissionId()), id(n));
        }
        data.actions(id);
        DelegateRow updated = delegation(app, id);
        reconcile(updated);
        changed(
                a,
                app,
                "DELEGATION_SAVE",
                id,
                old == null ? Map.of() : Map.of("version", old.version()),
                Map.of(
                        "enabled",
                        request.enabled(),
                        "peopleScope",
                        request.peopleScope(),
                        "commonRoleIds",
                        request.commonRoleIds(),
                        "permissionCeilings",
                        request.permissionCeilings()));
        return delegation(updated);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Delegation delegationStatus(Actor a, long app, long target, Status request) {
        platform(a, app, "admin:delegation:manage");
        DelegateRow old = delegation(app, target);
        Delegation d = delegation(old);
        return saveDelegation(
                a,
                app,
                target,
                new DelegationSet(
                        d.administratorUserId(),
                        request.enabled(),
                        d.validFrom(),
                        d.validTo(),
                        d.peopleScope(),
                        d.commonRoleIds(),
                        d.permissionCeilings(),
                        request.expectedVersion()));
    }

    private void reconcile(DelegateRow d) {
        boolean active = d.enabled() && within(d.validFrom(), d.validTo());
        for (long u : data.responsibleUsers(d.id()))
            if (!active || !contains(delegationPeople(d), user(u).organizationId()))
                data.setResponsibility(d.applicationId(), u, d.id(), "AWAITING");
    }

    public Responsibility responsibility(Actor a, long app, long target) {
        platform(a, app, "admin:delegation:view");
        user(target);
        return responsibilityView(app, target);
    }

    private void setResponsibility(long app, long target, Long delegation, Long prospectiveOrg) {
        if (delegation == null) {
            data.setResponsibility(app, target, null, "AWAITING");
            return;
        }
        DelegateRow d = delegation(app, delegation);
        activeDelegation(app, d.administratorUserId());
        if (!contains(delegationPeople(d), prospectiveOrg))
            throw failure(422, "RESPONSIBILITY_SCOPE_DENIED", "人员超出接管人组织范围");
        data.setResponsibility(app, target, delegation, "CURRENT");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Responsibility responsibility(
            Actor a, long app, long target, ResponsibilitySet request) {
        lock(a, app, "admin:responsibility:manage", List.of(target));
        platform(a, app, "admin:responsibility:manage");
        Responsibility before = data.responsibility(app, target);
        if (before == null) {
            if (request.expectedVersion() != null)
                throw failure(409, "VERSION_CONFLICT", "新增责任版本应为空");
        } else {
            if (request.expectedVersion() == null)
                throw failure(400, "VERSION_REQUIRED", "请提供责任版本");
            version(before.version(), request.expectedVersion());
        }
        // 单独交接不能把现有专用角色交给不同维护人；须使用角色整体交接预览。
        Long next = request.delegationId() == null ? null : id(request.delegationId());
        for (Membership m : data.memberships(app, target))
            if (m.kind().equals("SCOPED")
                    && next != null
                    && role(app, id(m.roleId())).ownerUserId()
                            != delegation(app, next).administratorUserId())
                throw failure(409, "SCOPED_ROLE_OWNER_MISMATCH", "请通过角色维护人整体交接处理现有专用角色");
        setResponsibility(app, target, next, user(target).organizationId());
        changed(
                a,
                app,
                "PERSON_RESPONSIBILITY",
                target,
                before == null ? Map.of() : Map.of("responsibility", before),
                Map.of("responsibility", data.responsibility(app, target)));
        return data.responsibility(app, target);
    }

    private void validateOwner(long app, long target, RoleOwner request) {
        RoleRow role = role(app, target);
        version(role.version(), request.expectedVersion());
        if (!role.kind().equals("SCOPED"))
            throw failure(422, "SCOPED_ROLE_REQUIRED", "仅专用角色支持维护人交接");
        long owner = id(request.newAdministratorUserId());
        owner(app, owner, rolePeople(role));
        DelegateRow d = activeDelegation(app, owner);
        for (long u : data.memberIds(target)) {
            if (!contains(delegationPeople(d), user(u).organizationId()) || protectedUser(u))
                throw failure(422, "OWNER_SCOPE_DENIED", "接管人范围不覆盖全部普通成员");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview previewOwner(Actor a, long app, long target, RoleOwner request) {
        long tree = lock(a, app, "admin:role:transfer");
        platform(a, app, "admin:role:transfer");
        validateOwner(app, target, request);
        RoleRow role = role(app, target);
        return preview(
                a,
                "ROLE_OWNER",
                target,
                app,
                role.version(),
                tree,
                request,
                role.memberCount(),
                Map.of("ownerUserId", sid(role.ownerUserId())),
                Map.of(
                        "ownerUserId",
                        request.newAdministratorUserId(),
                        "awaitingUsers",
                        data.memberIds(target).stream()
                                .filter(
                                        u ->
                                                !consistentOwner(
                                                        app,
                                                        u,
                                                        target,
                                                        id(request.newAdministratorUserId())))
                                .count()));
    }

    private boolean consistentOwner(long app, long user, long transferredRole, long owner) {
        return data.memberships(app, user).stream()
                .filter(m -> m.kind().equals("SCOPED") && id(m.roleId()) != transferredRole)
                .allMatch(m -> Objects.equals(role(app, id(m.roleId())).ownerUserId(), owner));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Role transferOwner(Actor a, long app, long target, Commit request) {
        long tree = lock(a, app, "admin:role:transfer");
        platform(a, app, "admin:role:transfer");
        PreviewRow p = load(a, "ROLE_OWNER", target, app, request.previewToken(), tree);
        RoleOwner r = decode(p.payloadJson(), RoleOwner.class);
        validateOwner(app, target, r);
        long before = role(app, target).ownerUserId(), owner = id(r.newAdministratorUserId());
        data.roleOwner(target, owner);
        DelegateRow d = activeDelegation(app, owner);
        // 多个专用角色可以逐个交接；中间只将维护责任待接管，绝不要求先撤员工角色。
        for (long u : data.memberIds(target))
            data.setResponsibility(
                    app,
                    u,
                    d.id(),
                    consistentOwner(app, u, target, owner) ? "CURRENT" : "AWAITING");
        consume(p);
        changed(
                a,
                app,
                "ROLE_OWNER_TRANSFER",
                target,
                Map.of("ownerUserId", sid(before)),
                Map.of("ownerUserId", sid(owner)));
        return view(a, app, role(app, target), "admin:role:transfer");
    }

    private void lastAdmin() {
        if (data.platformCount(clock.instant()) < 1)
            throw failure(409, "LAST_PLATFORM_ADMIN", "不能移除最后一个启用的平台管理员");
    }

    private void changed(
            Actor a,
            long app,
            String action,
            long target,
            Map<String, ?> before,
            Map<String, ?> after) {
        data.bumpNamespace(app);
        audit.append(a.id(), a.traceId(), action, "AUTHORIZATION", target, before, after);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Authorization encoding failed", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Authorization command decoding failed", e);
        }
    }

    private Map<String, Long> namespaces(Long app) {
        Map<String, Long> v = new TreeMap<>();
        for (App a : data.apps())
            if (app == null || a.id() == 1 || a.id() == app)
                v.put(sid(a.id()), data.namespace(a.id()));
        return v;
    }

    private Preview preview(
            Actor a,
            String kind,
            long target,
            Long app,
            long targetVersion,
            long tree,
            Object payload,
            long affected,
            Map<String, ?> before,
            Map<String, ?> after) {
        String token = UUID.randomUUID().toString();
        Instant expires = clock.instant().plusSeconds(900);
        Map<String, Long> versions = namespaces(app);
        data.preview(
                new PreviewRow(
                        token,
                        a.id(),
                        kind,
                        target,
                        app,
                        targetVersion,
                        tree,
                        encode(versions),
                        encode(payload),
                        expires,
                        false));
        return new Preview(
                token,
                kind,
                sid(target),
                app == null ? null : sid(app),
                targetVersion,
                tree,
                versions,
                affected,
                expires,
                before,
                after);
    }

    private PreviewRow load(Actor a, String kind, long target, Long app, String token, long tree) {
        PreviewRow p = data.previewForUpdate(token);
        if (p == null
                || p.actorUserId() != a.id()
                || !p.kind().equals(kind)
                || p.targetId() != target
                || !Objects.equals(p.applicationId(), app)
                || p.consumed()) throw failure(409, "INVALID_PREVIEW", "预览不存在、不属于当前操作或已使用");
        if (!clock.instant().isBefore(p.expiresAt()))
            throw failure(410, "PREVIEW_EXPIRED", "预览已过期");
        version(p.treeVersion(), tree);
        try {
            Map<String, Long> expected =
                    json.readValue(p.namespaceJson(), new TypeReference<Map<String, Long>>() {});
            if (!expected.equals(namespaces(app)))
                throw failure(409, "STALE_AUTHORIZATION_PREVIEW", "授权配置已变化，请重新预览");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Preview snapshot invalid", e);
        }
        return p;
    }

    private void consume(PreviewRow p) {
        if (data.consume(p.token()) != 1) throw failure(409, "INVALID_PREVIEW", "预览已消费");
    }

    /** 调岗/离职由组合层先持目录独占锁，再按应用ID持命名空间，最后锁账号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAllNamespaces() {
        for (App app : data.apps()) data.lockNamespace(app.id());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTransfer(Actor a, Long user) {
        lockAllNamespaces();
        data.lockLastAdmin();
        TreeSet<Long> ids = new TreeSet<>();
        ids.add(a.id());
        if (user != null) ids.add(user);
        for (long u : ids) data.lockUser(u);
        platform(a, 1, "admin:employee:transfer");
    }

    private void validateTransfer(Actor a, Long user, EmployeeTransfer request) {
        if (!Set.of("KEEP", "RETIRE").contains(request.managementAction()))
            throw failure(422, "INVALID_MANAGEMENT_ACTION", "须明确保留或撤任本人管理资格");
        unique(request.applications(), TransferApplication::applicationId);
        if (user == null) {
            if (request.expectedAccountVersion() != null
                    || request.applications().stream()
                            .anyMatch(
                                    v ->
                                            !v.memberships().isEmpty()
                                                    || v.responsibilityDelegationId() != null))
                throw failure(422, "ACCOUNT_REQUIRED", "无账号人员不能分配角色或维护人");
            return;
        }
        User u = user(user);
        if (request.expectedAccountVersion() == null)
            throw failure(400, "VERSION_REQUIRED", "须提供账号版本");
        version(u.version(), request.expectedAccountVersion());
        eligible(u);
        boolean prospectiveAdmin =
                request.managementAction().equals("KEEP")
                        && data.potentialDelegate(user, clock.instant()) > 0;
        // 将来平台角色也作为管理员保护目标，禁止与本人维护的专用业务角色并存。
        for (var app : request.applications())
            for (var member : app.memberships())
                if (id(app.applicationId()) == 1
                        && definition(id(member.roleId())).stream()
                                .anyMatch(
                                        p ->
                                                data.permissions(1).stream()
                                                        .anyMatch(
                                                                x ->
                                                                        x.id().equals(
                                                                                                p
                                                                                                        .permissionId())
                                                                                && x.code()
                                                                                        .equals(
                                                                                                "admin:platform:manage"))))
                    prospectiveAdmin = true;
        for (var app : request.applications()) {
            long application = id(app.applicationId());
            application(application);
            unique(app.memberships(), MembershipInput::roleId);
            DelegateRow responsibility =
                    app.responsibilityDelegationId() == null
                            ? null
                            : delegation(application, id(app.responsibilityDelegationId()));
            if (responsibility != null) {
                activeDelegation(application, responsibility.administratorUserId());
                if (!contains(delegationPeople(responsibility), id(request.organizationId())))
                    throw failure(422, "RESPONSIBILITY_SCOPE_DENIED", "新组织不在接管人的管理范围");
                if (request.managementAction().equals("RETIRE")
                        && responsibility.administratorUserId() == user)
                    throw failure(422, "RETIRED_RESPONSIBILITY", "离任后不能由本人接管");
            }
            for (var m : app.memberships()) {
                RoleRow role = role(application, id(m.roleId()));
                times(m.validFrom(), m.validTo());
                if (!role.enabled()) throw failure(422, "ROLE_DISABLED", "新角色未启用");
                if (!contains(rolePeople(role), id(request.organizationId())))
                    throw failure(422, "ROLE_PERSONNEL_SCOPE_DENIED", "新组织不在角色人员范围");
                if (role.kind().equals("SCOPED")) {
                    owner(application, role.ownerUserId(), rolePeople(role));
                    if (prospectiveAdmin
                            || responsibility == null
                            || role.ownerUserId() != responsibility.administratorUserId())
                        throw failure(422, "SCOPED_RESPONSIBILITY_REQUIRED", "专用角色须分配给普通人员并匹配接管人");
                }
                for (var p : definition(role.id())) validPermission(application, p.permissionId());
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Preview previewTransfer(
            Actor a, long employee, Long user, long tree, EmployeeTransfer request) {
        validateTransfer(a, user, request);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("userId", user == null ? null : sid(user));
        before.put(
                "applications",
                user == null
                        ? List.of()
                        : data.apps().stream().map(app -> userRoles(app.id(), user)).toList());
        return preview(
                a,
                "EMPLOYEE_TRANSFER",
                employee,
                null,
                request.expectedVersion(),
                tree,
                request,
                1,
                before,
                Map.of(
                        "organizationId",
                        request.organizationId(),
                        "managementAction",
                        request.managementAction(),
                        "applications",
                        request.applications()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EmployeeTransfer readTransfer(Actor a, long employee, long tree, String token) {
        return decode(
                load(a, "EMPLOYEE_TRANSFER", employee, null, token, tree).payloadJson(),
                EmployeeTransfer.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UserRoles> applyTransfer(
            Actor a, long employee, Long user, long tree, EmployeeTransfer request, String token) {
        PreviewRow p = load(a, "EMPLOYEE_TRANSFER", employee, null, token, tree);
        validateTransfer(a, user, request);
        List<UserRoles> result = new ArrayList<>();
        if (user != null) {
            var before = data.apps().stream().map(app -> userRoles(app.id(), user)).toList();
            if (request.managementAction().equals("RETIRE")) retireManagement(a, user);
            // 明确撤回本人所有旧关系（包括历史角色）；不触碰其他账号的成员关系。
            for (App app : data.apps())
                for (Membership membership : data.memberships(app.id(), user))
                    data.removeMember(user, id(membership.roleId()));
            // LEGACY不出现在应用列表，旧关系由身份公开入口统一撤回。
            for (App app : data.apps()) {
                TransferApplication requested =
                        request.applications().stream()
                                .filter(x -> id(x.applicationId()) == app.id())
                                .findFirst()
                                .orElse(null);
                List<MembershipInput> memberships =
                        requested == null ? List.of() : requested.memberships();
                writeUserRoles(a, app.id(), user, memberships);
                setResponsibility(
                        app.id(),
                        user,
                        requested == null || requested.responsibilityDelegationId() == null
                                ? null
                                : id(requested.responsibilityDelegationId()),
                        id(request.organizationId()));
                result.add(userRoles(app.id(), user));
                data.bumpNamespace(app.id());
            }
            lastAdmin();
            audit.append(
                    a.id(),
                    a.traceId(),
                    "EMPLOYEE_ROLE_REPLACEMENT",
                    "ACCOUNT",
                    user,
                    Map.of("applications", before),
                    Map.of("applications", result, "managementAction", request.managementAction()));
        }
        consume(p);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void retireManagement(Actor a, long user) {
        // 调用方持目录独占锁/所有应用锁；撤任只改变资格和维护责任，不撤员工历史角色。
        for (App app : data.apps()) {
            DelegateRow d = data.ownDelegation(app.id(), user);
            if (d == null) continue;
            var people = data.responsibleUsers(d.id());
            for (long target : people) data.setResponsibility(app.id(), target, d.id(), "AWAITING");
            audit.append(
                    a.id(),
                    a.traceId(),
                    "ADMINISTRATOR_RETIRE",
                    "AUTHORIZATION",
                    d.id(),
                    Map.of(
                            "enabled",
                            d.enabled(),
                            "responsibleUserIds",
                            people.stream().map(this::sid).toList()),
                    Map.of("enabled", false, "responsibilityState", "AWAITING"));
            data.bumpNamespace(app.id());
        }
        data.retireDelegations(user);
    }
}

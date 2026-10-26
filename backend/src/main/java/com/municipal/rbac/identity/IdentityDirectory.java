/** 功能：组织/人员/账号规则及公开持久化入口；administration负责权限、外层事务及同事务审计。 */
package com.municipal.rbac.identity;

import static com.municipal.rbac.identity.IdentityData.*;

import com.municipal.rbac.identity.internal.IdentityMapper;
import com.municipal.rbac.shared.PageResult;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityDirectory {
    private final IdentityMapper data;
    private final AccountMapper credentials;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final boolean demo;
    private final SecureRandom random = new SecureRandom();

    public IdentityDirectory(
            IdentityMapper data,
            AccountMapper credentials,
            PasswordHasher passwords,
            Clock clock,
            Environment environment) {
        this.data = data;
        this.credentials = credentials;
        this.passwords = passwords;
        this.clock = clock;
        this.demo = environment.acceptsProfiles(Profiles.of("demo"));
    }

    public long lockTree() {
        return data.lockGuard(1);
    }

    public void lockAdministratorProtection() {
        data.lockGuard(2);
    }

    public AccountState lockAccount(long id) {
        AccountState state = data.lockAccount(id);
        if (state == null) throw failure(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        return state;
    }

    public AccountState accountState(long id) {
        Account a = credentials.byId(id);
        if (a == null) throw failure(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        return new AccountState(a.id(), a.enabled(), a.authVersion(), account(id).version());
    }

    public void lockAccounts(long actor, long target) {
        // 稳定顺序锁定操作者与目标，交叉管理不会反向持锁。
        lockAccount(Math.min(actor, target));
        if (actor != target) lockAccount(Math.max(actor, target));
    }

    public List<Organization> organizations() {
        return data.organizations();
    }

    public Organization organization(long id) {
        Organization row = data.organization(id);
        if (row == null) throw failure(404, "ORGANIZATION_NOT_FOUND", "组织不存在");
        return row;
    }

    public Employee employee(long id) {
        Employee row = data.employee(id);
        if (row == null) throw failure(404, "EMPLOYEE_NOT_FOUND", "人员不存在");
        return row;
    }

    public UserAccount account(long id) {
        UserAccount row = data.account(id);
        if (row == null) throw failure(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        return row;
    }

    private void activeOrganization(long id) {
        Organization org = organization(id);
        if (!org.enabled() || data.disabledAncestors(id) > 0)
            throw failure(422, "ORGANIZATION_DISABLED", "组织或其上级未启用");
    }

    private void parent(String type, Long parentId) {
        if (!Set.of("HEADQUARTERS", "BRANCH", "DEPARTMENT").contains(type))
            throw failure(422, "INVALID_ORGANIZATION_TYPE", "组织类型无效");
        if (type.equals("HEADQUARTERS")) {
            if (parentId != null) throw failure(422, "INVALID_HIERARCHY", "总公司必须为根节点");
            return;
        }
        if (parentId == null) throw failure(422, "PARENT_REQUIRED", "非根组织必须指定上级");
        activeOrganization(parentId);
        Organization parent = organization(parentId);
        if (type.equals("BRANCH") && !parent.nodeType().equals("HEADQUARTERS"))
            throw failure(422, "INVALID_HIERARCHY", "分公司只能位于总公司下");
        if (data.depth(parentId) + 1 > 7) throw failure(422, "ORGANIZATION_DEPTH", "组织最多8层");
    }

    private void uniqueName(Long parentId, String name, long exclude) {
        if (data.siblings(parentId, nameKey(name), exclude) > 0)
            throw failure(409, "ORGANIZATION_NAME_CONFLICT", "同一上级下组织名称重复");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization createOrganization(
            String codeValue, String name, String type, Long parentId) {
        parent(type, parentId);
        if (parentId == null && data.roots() > 0) throw failure(409, "ROOT_EXISTS", "行政根已存在");
        String display = text(name, 160);
        uniqueName(parentId, display, 0);
        Map<String, Object> v = new HashMap<>();
        v.put("code", code(codeValue));
        v.put("name", display);
        v.put("nameKey", nameKey(display));
        v.put("nodeType", type);
        v.put("parentId", parentId);
        data.insertOrganization(v);
        long id = ((Number) v.get("id")).longValue();
        data.insertSelf(id);
        if (parentId != null) data.insertAncestors(id, parentId);
        data.bumpTree();
        return organization(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization updateOrganization(long id, String name, Long manager, long expected) {
        Organization old = organization(id);
        version(old.version(), expected);
        if (manager != null && !employee(manager).enabled())
            throw failure(422, "EMPLOYEE_DISABLED", "负责人须为在职人员");
        String display = text(name, 160);
        uniqueName(old.parentId() == null ? null : id(old.parentId()), display, id);
        Map<String, Object> v = new HashMap<>();
        v.put("id", id);
        v.put("name", display);
        v.put("nameKey", nameKey(display));
        v.put("managerId", manager);
        v.put("version", expected);
        changed(data.updateOrganization(v));
        data.bumpTree();
        return organization(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization organizationStatus(long id, boolean enabled, long expected) {
        Organization old = organization(id);
        version(old.version(), expected);
        if (old.enabled() == enabled) return old;
        if (!enabled && (data.enabledChildren(id) > 0 || data.activeEmployees(id) > 0))
            throw failure(409, "ORGANIZATION_IN_USE", "请先处理启用子组织及在职人员");
        if (enabled && old.parentId() != null) activeOrganization(id(old.parentId()));
        changed(data.organizationStatus(id, enabled, expected));
        data.bumpTree();
        return organization(id);
    }

    private Organization validateMove(long id, long parentId, long expected) {
        Organization org = organization(id);
        version(org.version(), expected);
        if (org.parentId() == null) throw failure(422, "ROOT_MOVE_FORBIDDEN", "行政根不可移动");
        if (data.path(id, parentId) > 0) throw failure(422, "ORGANIZATION_CYCLE", "组织不能移动到自己或后代下");
        if (id(org.parentId()) == parentId) throw failure(422, "MOVE_UNCHANGED", "上级未变化");
        parent(org.nodeType(), parentId);
        if (data.depth(parentId) + 1 + data.height(id) > 7)
            throw failure(422, "ORGANIZATION_DEPTH", "移动后超过8层");
        uniqueName(parentId, org.name(), id);
        if (data.affectedGrants(id) > 0 || data.authorizationScopeReferences() > 0)
            throw failure(409, "DEPENDENT_AUTHORIZATION_NOT_READY", "子树已有授权，须待角色范围影响分析接入后移动");
        return org;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Preview previewMove(
            long actor, long id, long parentId, long expected, long treeVersion) {
        validateMove(id, parentId, expected);
        String token = UUID.randomUUID().toString();
        var expires = clock.instant().plus(Duration.ofMinutes(15));
        data.preview(
                new PreviewState(
                        token, actor, id, parentId, expected, treeVersion, expires, false));
        return new Preview(
                token,
                Long.toString(id),
                Long.toString(parentId),
                expected,
                treeVersion,
                data.affectedEmployees(id),
                expires);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization move(long actor, long id, String token, long treeVersion) {
        PreviewState p = data.lockPreview(token);
        if (p == null || p.actorUserId() != actor || p.organizationId() != id || p.consumed())
            throw failure(409, "INVALID_PREVIEW", "预览无效或已使用");
        if (!clock.instant().isBefore(p.expiresAt()))
            throw failure(410, "PREVIEW_EXPIRED", "预览已过期");
        version(p.treeVersion(), treeVersion);
        validateMove(id, p.newParentId(), p.targetVersion());
        changed(data.move(id, p.newParentId(), p.targetVersion()));
        // 整树在目录锁内重建闭包；失败回滚旧树和全部路径，避免部分祖先路径。
        data.clearPaths();
        data.rebuildPaths();
        changed(data.consumePreview(token));
        data.bumpTree();
        return organization(id);
    }

    public PageResult<Employee> employees(
            int page,
            int size,
            String q,
            String sort,
            Boolean enabled,
            Long org,
            boolean descendants) {
        if (org != null) organization(org);
        Map<String, Object> v = page(page, size, q, sort, enabled);
        v.put("organizationId", org);
        v.put("includeDescendants", descendants);
        return new PageResult<>(data.employees(v), page, size, data.employeeCount(v));
    }

    private Map<String, Object> page(int page, int size, String q, String sort, Boolean enabled) {
        if (!Set.of("id", "name").contains(sort)) throw failure(400, "INVALID_SORT", "排序字段无效");
        Map<String, Object> v = new HashMap<>();
        v.put("offset", offset(page, size));
        v.put("size", size);
        v.put("q", search(q));
        v.put("sort", sort);
        v.put("enabled", enabled);
        return v;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Employee createEmployee(
            String employeeCode, String name, long org, String position, String phoneValue) {
        activeOrganization(org);
        Map<String, Object> v = new HashMap<>();
        v.put("employeeCode", code(employeeCode));
        v.put("displayName", text(name, 100));
        v.put("organizationId", org);
        v.put("positionName", position == null || position.isBlank() ? null : text(position, 100));
        v.put("phone", phone(phoneValue));
        data.insertEmployee(v);
        data.bumpTree();
        return employee(((Number) v.get("id")).longValue());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Employee updateEmployee(long id, String name, String phoneValue, long expected) {
        Employee old = data.lockEmployee(id);
        if (old == null) throw failure(404, "EMPLOYEE_NOT_FOUND", "人员不存在");
        version(old.version(), expected);
        Map<String, Object> v = new HashMap<>();
        v.put("id", id);
        v.put("displayName", text(name, 100));
        v.put("phone", phone(phoneValue));
        v.put("version", expected);
        changed(data.updateEmployee(v));
        return employee(id);
    }

    public Long employeeAccount(long employee) {
        return data.employeeAccount(employee);
    }

    public void validateAssignment(
            long employee, long organization, String position, long expected) {
        Employee e = employee(employee);
        version(e.version(), expected);
        if (!e.enabled()) throw failure(422, "EMPLOYEE_DISABLED", "请先启用人员");
        activeOrganization(organization);
        if (data.managedOrganizations(employee) > 0)
            throw failure(409, "MANAGER_IN_USE", "调岗前先解除组织负责人绑定");
        if (position != null && !position.isBlank()) text(position, 100);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Employee assignment(long employee, long organization, String position, long expected) {
        validateAssignment(employee, organization, position, expected);
        changed(
                data.assignment(
                        employee,
                        organization,
                        position == null || position.isBlank() ? null : text(position, 100),
                        expected));
        data.bumpTree();
        return employee(employee);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeLegacyMemberships(long user) {
        data.revokeLegacyMemberships(user);
    }

    public List<Membership> memberships(long user) {
        return data.memberships(user);
    }

    public void requireAnotherAdministrator(long target) {
        List<Long> administrators = data.platformAdmins(clock.instant());
        if (administrators.contains(target) && administrators.size() <= 1)
            throw failure(409, "LAST_PLATFORM_ADMIN", "不能停用最后一个启用的平台管理员");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Employee employeeStatus(long id, boolean enabled, long expected) {
        Employee old = data.lockEmployee(id);
        if (old == null) throw failure(404, "EMPLOYEE_NOT_FOUND", "人员不存在");
        version(old.version(), expected);
        if (old.enabled() == enabled) return old;
        if (enabled) activeOrganization(id(old.organizationId()));
        if (!enabled && data.managedOrganizations(id) > 0)
            throw failure(409, "MANAGER_IN_USE", "请先解除该人员的组织负责人绑定");
        Long user = data.employeeAccount(id);
        if (!enabled && user != null) {
            requireAnotherAdministrator(user);
            UserAccount account = account(user);
            changed(data.accountStatus(user, false, account.version()));
            // 只撤回目标本人关系；不删除角色定义，也不检查或撤回其历史授予对象。
            data.revokeOwnRoles(user);
        }
        changed(data.employeeStatus(id, enabled, expected));
        data.bumpTree();
        return employee(id);
    }

    public PageResult<UserAccount> accounts(
            int page, int size, String q, String sort, Boolean enabled) {
        var v = page(page, size, q, sort, enabled);
        return new PageResult<>(data.accounts(v), page, size, data.accountCount(v));
    }

    private String temporary() {
        if (demo) return "1234";
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryCredential createAccount(String username, long employeeId) {
        Employee e = data.lockEmployee(employeeId);
        if (e == null) throw failure(404, "EMPLOYEE_NOT_FOUND", "人员不存在");
        if (!e.enabled()) throw failure(422, "EMPLOYEE_DISABLED", "停用人员不可建号");
        activeOrganization(id(e.organizationId()));
        String password = temporary();
        Map<String, Object> v = new HashMap<>();
        v.put("username", code(username));
        v.put("employeeId", employeeId);
        v.put("hash", passwords.encode(password));
        v.put("force", !demo);
        data.insertAccount(v);
        return new TemporaryCredential(account(((Number) v.get("id")).longValue()), password);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount accountStatus(long id, boolean enabled, long expected) {
        UserAccount old = account(id);
        version(old.version(), expected);
        if (old.enabled() == enabled) return old;
        if (!enabled) requireAnotherAdministrator(id);
        else if (old.employeeId() != null) {
            Employee e = employee(id(old.employeeId()));
            if (!e.enabled()) throw failure(422, "EMPLOYEE_DISABLED", "请先启用人员");
            activeOrganization(id(e.organizationId()));
        }
        changed(data.accountStatus(id, enabled, expected));
        return account(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryCredential resetPassword(long id, long expected) {
        UserAccount old = account(id);
        version(old.version(), expected);
        String password = temporary();
        changed(data.password(id, passwords.encode(password), !demo, expected));
        return new TemporaryCredential(account(id), password);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changePassword(long id, String current, String replacement) {
        if (replacement == null
                || replacement.length() < 12
                || replacement.getBytes(StandardCharsets.UTF_8).length > 72)
            throw failure(422, "PASSWORD_POLICY", "新密码至少12字符且最多72个UTF-8字节");
        Account account = credentials.byId(id);
        if (account == null || !passwords.matches(current, account.passwordHash()))
            throw failure(422, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        if (passwords.matches(replacement, account.passwordHash()))
            throw failure(422, "PASSWORD_REUSED", "新密码不能与当前密码相同");
        UserAccount view = account(id);
        changed(data.password(id, passwords.encode(replacement), false, view.version()));
    }

    private void changed(int rows) {
        if (rows != 1) throw failure(409, "VERSION_CONFLICT", "记录已变化，请重新加载");
    }
}

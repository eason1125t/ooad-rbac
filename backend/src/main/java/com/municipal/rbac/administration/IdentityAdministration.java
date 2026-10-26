/** 功能：身份管理组合根；统一权限核验、稳定顺序锁及资料/身份版本/审计的同事务提交。 */
package com.municipal.rbac.administration;

import static com.municipal.rbac.identity.IdentityData.*;

import com.municipal.rbac.audit.AuditLog;
import com.municipal.rbac.authorization.*;
import com.municipal.rbac.identity.IdentityDirectory;
import com.municipal.rbac.shared.PageResult;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class IdentityAdministration {
    public record Actor(long id, long authVersion, String sessionId, String traceId) {
        @Override
        public String toString() {
            return "Actor[" + id + "]";
        }
    }

    private final IdentityDirectory directory;
    private final PermissionChecker permissions;
    private final AuditLog audit;
    private final AccessManager access;

    public IdentityAdministration(
            IdentityDirectory directory,
            PermissionChecker permissions,
            AuditLog audit,
            AccessManager access) {
        this.directory = directory;
        this.permissions = permissions;
        this.audit = audit;
        this.access = access;
    }

    private void actor(Actor a) {
        AccountState current = directory.accountState(a.id());
        if (!current.enabled() || current.authVersion() != a.authVersion())
            throw failure(401, "UNAUTHENTICATED", "登录已失效");
    }

    private void platform(Actor a, String operation) {
        actor(a);
        // 沿用同一个权限核心；写事务内复核最新主体/权限，防止请求入口之后撤权。
        if (!permissions.allowsAll(
                new PermissionContext(a.id(), a.sessionId(), "admin", operation, Map.of()),
                Set.of(operation, "admin:platform:manage")))
            throw failure(403, "FORBIDDEN", "需要相应的平台管理权限");
    }

    private void beginTree(Actor a, String permission) {
        directory.lockTree();
        directory.lockAccount(a.id());
        platform(a, permission);
    }

    private void log(
            Actor a,
            String action,
            String kind,
            String target,
            Map<String, ?> before,
            Map<String, ?> after) {
        audit.append(a.id(), a.traceId(), action, kind, id(target), before, after);
    }

    public List<Organization> organizations(Actor a) {
        platform(a, "admin:organization:view");
        return directory.organizations();
    }

    public Organization organization(Actor a, long id) {
        platform(a, "admin:organization:view");
        return directory.organization(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Organization createOrganization(Actor a, IdentityRequests.OrganizationCreate r) {
        beginTree(a, "admin:organization:create");
        var row =
                directory.createOrganization(
                        r.code(), r.name(), r.nodeType(), nullableId(r.parentId()));
        log(
                a,
                "ORGANIZATION_CREATE",
                "ORGANIZATION",
                row.id(),
                null,
                Map.of("version", row.version(), "nodeType", row.nodeType()));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Organization updateOrganization(
            Actor a, long id, IdentityRequests.OrganizationUpdate r) {
        beginTree(a, "admin:organization:update");
        var old = directory.organization(id);
        var row =
                directory.updateOrganization(
                        id, r.name(), nullableId(r.managerEmployeeId()), r.expectedVersion());
        log(
                a,
                "ORGANIZATION_UPDATE",
                "ORGANIZATION",
                row.id(),
                Map.of("version", old.version()),
                Map.of(
                        "version",
                        row.version(),
                        "changedFields",
                        List.of("name", "managerEmployeeId")));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Organization organizationStatus(Actor a, long id, IdentityRequests.Status r) {
        beginTree(a, "admin:organization:status");
        var old = directory.organization(id);
        var row = directory.organizationStatus(id, r.enabled(), r.expectedVersion());
        log(
                a,
                "ORGANIZATION_STATUS",
                "ORGANIZATION",
                row.id(),
                Map.of("enabled", old.enabled(), "version", old.version()),
                Map.of("enabled", row.enabled(), "version", row.version()));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview preview(Actor a, long id, IdentityRequests.MovePreview r) {
        long tree = directory.lockTree();
        directory.lockAccount(a.id());
        platform(a, "admin:organization:move");
        return directory.previewMove(a.id(), id, id(r.parentId()), r.expectedVersion(), tree);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Organization move(Actor a, long id, IdentityRequests.MoveCommit r) {
        long tree = directory.lockTree();
        directory.lockAccount(a.id());
        platform(a, "admin:organization:move");
        var old = directory.organization(id);
        var row = directory.move(a.id(), id, r.previewToken(), tree);
        log(
                a,
                "ORGANIZATION_MOVE",
                "ORGANIZATION",
                row.id(),
                Map.of("parentId", old.parentId(), "version", old.version()),
                Map.of("parentId", row.parentId(), "version", row.version()));
        return row;
    }

    public PageResult<Employee> employees(
            Actor a,
            int page,
            int size,
            String q,
            String sort,
            Boolean enabled,
            Long org,
            boolean descendants) {
        platform(a, "admin:employee:view");
        return directory.employees(page, size, q, sort, enabled, org, descendants);
    }

    public Employee employee(Actor a, long id) {
        platform(a, "admin:employee:view");
        return directory.employee(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Employee createEmployee(Actor a, IdentityRequests.EmployeeCreate r) {
        beginTree(a, "admin:employee:create");
        var row =
                directory.createEmployee(
                        r.employeeCode(),
                        r.displayName(),
                        id(r.organizationId()),
                        r.positionName(),
                        r.phone());
        log(
                a,
                "EMPLOYEE_CREATE",
                "EMPLOYEE",
                row.id(),
                null,
                Map.of("organizationId", row.organizationId(), "version", row.version()));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Employee updateEmployee(Actor a, long id, IdentityRequests.EmployeeUpdate r) {
        directory.lockAccount(a.id());
        platform(a, "admin:employee:update");
        var row = directory.updateEmployee(id, r.displayName(), r.phone(), r.expectedVersion());
        log(
                a,
                "EMPLOYEE_UPDATE",
                "EMPLOYEE",
                row.id(),
                Map.of("version", r.expectedVersion()),
                Map.of("version", row.version(), "changedFields", List.of("displayName", "phone")));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Employee employeeStatus(Actor a, long id, IdentityRequests.Status r) {
        directory.lockTree();
        access.lockAllNamespaces();
        directory.lockAdministratorProtection();
        Long target = directory.employeeAccount(id);
        directory.lockAccounts(a.id(), target == null ? a.id() : target);
        platform(a, "admin:employee:status");
        var old = directory.employee(id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("enabled", old.enabled());
        before.put("version", old.version());
        if (!r.enabled() && old.enabled() && target != null) {
            // 删除关系前保留角色ID/有效期；不记录姓名/口令，撤回历史仍可追溯。
            before.put("accountId", Long.toString(target));
            before.put("memberships", directory.memberships(target));
        }
        var row = directory.employeeStatus(id, r.enabled(), r.expectedVersion());
        if (!r.enabled() && old.enabled() && target != null)
            access.retireManagement(
                    new AccessData.Actor(a.id(), a.authVersion(), a.sessionId(), a.traceId()),
                    target);
        log(
                a,
                "EMPLOYEE_STATUS",
                "EMPLOYEE",
                row.id(),
                before,
                Map.of("enabled", row.enabled(), "version", row.version()));
        return row;
    }

    public PageResult<UserAccount> accounts(
            Actor a, int page, int size, String q, String sort, Boolean enabled) {
        platform(a, "admin:account:view");
        return directory.accounts(page, size, q, sort, enabled);
    }

    public UserAccount account(Actor a, long id) {
        platform(a, "admin:account:view");
        return directory.account(id);
    }

    public UserAccount employeeAccount(Actor a, long employee) {
        platform(a, "admin:account:view");
        directory.employee(employee);
        Long account = directory.employeeAccount(employee);
        return account == null ? null : directory.account(account);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemporaryCredential createAccount(Actor a, IdentityRequests.AccountCreate r) {
        beginTree(a, "admin:account:create");
        var result = directory.createAccount(r.username(), id(r.employeeId()));
        log(
                a,
                "ACCOUNT_CREATE",
                "ACCOUNT",
                result.account().id(),
                null,
                Map.of(
                        "employeeId",
                        result.account().employeeId(),
                        "forcePasswordChange",
                        result.account().forcePasswordChange()));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserAccount accountStatus(Actor a, long id, IdentityRequests.Status r) {
        directory.lockTree();
        directory.lockAdministratorProtection();
        directory.lockAccounts(a.id(), id);
        platform(a, "admin:account:status");
        var old = directory.account(id);
        var row = directory.accountStatus(id, r.enabled(), r.expectedVersion());
        log(
                a,
                "ACCOUNT_STATUS",
                "ACCOUNT",
                row.id(),
                Map.of("enabled", old.enabled(), "version", old.version()),
                Map.of("enabled", row.enabled(), "version", row.version()));
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemporaryCredential resetPassword(Actor a, long id, IdentityRequests.PasswordReset r) {
        directory.lockAccounts(a.id(), id);
        platform(a, "admin:account:reset-password");
        var result = directory.resetPassword(id, r.expectedVersion());
        log(
                a,
                "ACCOUNT_PASSWORD_RESET",
                "ACCOUNT",
                result.account().id(),
                Map.of("version", r.expectedVersion()),
                Map.of(
                        "version",
                        result.account().version(),
                        "forcePasswordChange",
                        result.account().forcePasswordChange()));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changePassword(Actor a, String old, String replacement) {
        directory.lockAccount(a.id());
        actor(a);
        directory.changePassword(a.id(), old, replacement);
        log(
                a,
                "ACCOUNT_PASSWORD_CHANGE",
                "ACCOUNT",
                Long.toString(a.id()),
                null,
                Map.of("identityRevoked", true));
    }

    public PageResult<AuditLog.Event> audit(Actor a, int page, int size) {
        platform(a, "admin:audit:view");
        return audit.page(page, size);
    }

    private static Long nullableId(String value) {
        return value == null ? null : id(value);
    }
}

/** 功能：人员调岗组合事务；身份资料与授权公开接口共同提交，任一失败回滚预览/角色/审计。 */
package com.municipal.rbac.administration;

import static com.municipal.rbac.identity.IdentityData.id;

import com.municipal.rbac.audit.AuditLog;
import com.municipal.rbac.authorization.*;
import com.municipal.rbac.authorization.AccessData.*;
import com.municipal.rbac.identity.IdentityDirectory;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class EmployeeAssignment {
    private final IdentityDirectory people;
    private final AccessManager access;
    private final AuditLog audit;

    public EmployeeAssignment(IdentityDirectory people, AccessManager access, AuditLog audit) {
        this.people = people;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview preview(Actor actor, long employee, EmployeeTransfer request) {
        long tree = people.lockTree();
        Long user = people.employeeAccount(employee);
        access.lockTransfer(actor, user);
        people.validateAssignment(
                employee,
                id(request.organizationId()),
                request.positionName(),
                request.expectedVersion());
        return access.previewTransfer(actor, employee, user, tree, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> commit(Actor actor, long employee, Commit command) {
        long tree = people.lockTree();
        Long user = people.employeeAccount(employee);
        access.lockTransfer(actor, user);
        EmployeeTransfer request =
                access.readTransfer(actor, employee, tree, command.previewToken());
        people.validateAssignment(
                employee,
                id(request.organizationId()),
                request.positionName(),
                request.expectedVersion());
        var old = people.employee(employee);
        // 本人旧LEGACY关系也撤回，最后平台管理员检查位于后续授权事务，失败会一起回滚。
        if (user != null) people.revokeLegacyMemberships(user);
        var applications =
                access.applyTransfer(actor, employee, user, tree, request, command.previewToken());
        var updated =
                people.assignment(
                        employee,
                        id(request.organizationId()),
                        request.positionName(),
                        request.expectedVersion());
        audit.append(
                actor.id(),
                actor.traceId(),
                "EMPLOYEE_ASSIGNMENT",
                "EMPLOYEE",
                employee,
                Map.of("organizationId", old.organizationId(), "version", old.version()),
                Map.of(
                        "organizationId",
                        updated.organizationId(),
                        "version",
                        updated.version(),
                        "positionChanged",
                        !Objects.equals(old.positionName(), updated.positionName())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employee", updated);
        result.put("userId", user == null ? null : Long.toString(user));
        result.put("applications", applications);
        return result;
    }
}

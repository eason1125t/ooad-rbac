/** 功能：V3管理HTTP适配，与唯一OpenAPI对应；所有操作者来自Session主体，逐方法声明权限和CSRF由统一链处理。 */
package com.municipal.rbac.administration;

import static com.municipal.rbac.identity.IdentityData.id;

import com.municipal.rbac.authorization.AccessData.*;
import com.municipal.rbac.authorization.AccessManager;
import com.municipal.rbac.security.*;
import com.municipal.rbac.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccessController {
    private final AccessManager service;
    private final EmployeeAssignment assignment;

    public AccessController(AccessManager service, EmployeeAssignment assignment) {
        this.service = service;
        this.assignment = assignment;
    }

    private Actor actor(IdentityPrincipal p, HttpServletRequest r) {
        return new Actor(
                p.id(),
                p.authVersion(),
                r.getSession(false).getId(),
                String.valueOf(r.getAttribute("traceId")));
    }

    private ResponseEntity<?> result(Object value, int status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(value));
    }

    @GetMapping("/api/v1/admin/managed-applications")
    @PreAuthorize("@permissionBridge.managementConsole(authentication, 'admin:application:view')")
    public ResponseEntity<?> listManagedApplications(
            @AuthenticationPrincipal IdentityPrincipal p, HttpServletRequest request) {
        Actor a = actor(p, request);
        return result(Map.of("items", service.applications(a)), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/permissions")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:view')")
    public ResponseEntity<?> listApplicationPermissions(
            @PathVariable String applicationId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(Map.of("items", service.permissions(a, app)), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/organizations")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:view')")
    public ResponseEntity<?> listApplicationOrganizations(
            @PathVariable String applicationId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(
                Map.of("items", service.organizations(actor(p, request), id(applicationId))), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/roles")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:view')")
    public ResponseEntity<?> listApplicationRoles(
            @PathVariable String applicationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(service.roles(a, app, page, size, q), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:create')")
    public ResponseEntity<?> createApplicationRole(
            @PathVariable String applicationId,
            @Valid @RequestBody RoleCreate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(service.createRole(a, app, r), 201);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:view')")
    public ResponseEntity<?> getApplicationRole(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.role(a, app, role), 200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:update')")
    public ResponseEntity<?> updateApplicationRole(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody RoleUpdate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.updateRole(a, app, role, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/status")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:status')")
    public ResponseEntity<?> changeApplicationRoleStatus(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody Status r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.roleStatus(a, app, role, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/owner-preview")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:role:transfer')")
    public ResponseEntity<?> previewRoleOwnerTransfer(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody RoleOwner r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.previewOwner(a, app, role, r), 200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/owner")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:role:transfer')")
    public ResponseEntity<?> transferRoleOwner(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody Commit r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.transferOwner(a, app, role, r), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/permissions")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:view')")
    public ResponseEntity<?> getRolePermissions(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.rolePermissions(a, app, role), 200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/permissions")
    @PreAuthorize(
            "@permissionBridge.managed(authentication, #applicationId, 'admin:role:permissions')")
    public ResponseEntity<?> replaceRolePermissions(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody Commit r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.replacePermissions(a, app, role, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/permissions/preview")
    @PreAuthorize(
            "@permissionBridge.managed(authentication, #applicationId, 'admin:role:permissions')")
    public ResponseEntity<?> previewRolePermissions(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody PermissionSet r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.previewPermissions(a, app, role, r), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/members")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:view')")
    public ResponseEntity<?> listRoleMembers(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.members(a, app, role, page, size, q), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/members")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:members')")
    public ResponseEntity<?> addRoleMembers(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody MemberBatch r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.addMembers(a, app, role, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/roles/{roleId}/members/remove")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:role:members')")
    public ResponseEntity<?> removeRoleMember(
            @PathVariable String applicationId,
            @PathVariable String roleId,
            @Valid @RequestBody MemberRemoval r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long role = id(roleId);
        return result(service.removeMember(a, app, role, r), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/people")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:view')")
    public ResponseEntity<?> listManageablePeople(
            @PathVariable String applicationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(service.people(a, app, page, size, q), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/users/{userId}/roles")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:view')")
    public ResponseEntity<?> getUserApplicationRoles(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.userRoles(a, app, user), 200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/users/{userId}/roles")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:update')")
    public ResponseEntity<?> replaceUserApplicationRoles(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @Valid @RequestBody Commit r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.replaceUserRoles(a, app, user, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/users/{userId}/roles/preview")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:update')")
    public ResponseEntity<?> previewUserApplicationRoles(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @Valid @RequestBody UserRoleSet r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.previewUserRoles(a, app, user, r), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/users/{userId}/effective-permissions")
    @PreAuthorize("@permissionBridge.managed(authentication, #applicationId, 'admin:grant:view')")
    public ResponseEntity<?> getUserEffectivePermissions(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.userEffective(a, app, user), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/delegations")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:delegation:view')")
    public ResponseEntity<?> listApplicationDelegations(
            @PathVariable String applicationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(service.delegations(a, app, page, size, q), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/delegations")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:delegation:manage')")
    public ResponseEntity<?> createApplicationDelegation(
            @PathVariable String applicationId,
            @Valid @RequestBody DelegationSet r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        return result(service.saveDelegation(a, app, null, r), 201);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/delegations/{delegationId}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:delegation:manage')")
    public ResponseEntity<?> updateApplicationDelegation(
            @PathVariable String applicationId,
            @PathVariable String delegationId,
            @Valid @RequestBody DelegationSet r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long delegation = id(delegationId);
        return result(service.saveDelegation(a, app, delegation, r), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/delegations/{delegationId}/status")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:delegation:manage')")
    public ResponseEntity<?> changeApplicationDelegationStatus(
            @PathVariable String applicationId,
            @PathVariable String delegationId,
            @Valid @RequestBody Status r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long delegation = id(delegationId);
        return result(service.delegationStatus(a, app, delegation, r), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/responsibilities/{userId}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:delegation:view')")
    public ResponseEntity<?> getUserResponsibility(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.responsibility(a, app, user), 200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/responsibilities/{userId}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:responsibility:manage')")
    public ResponseEntity<?> setUserResponsibility(
            @PathVariable String applicationId,
            @PathVariable String userId,
            @Valid @RequestBody ResponsibilitySet r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        long app = id(applicationId);
        long user = id(userId);
        return result(service.responsibility(a, app, user, r), 200);
    }

    @PostMapping("/api/v1/admin/employees/{id}/assignment-preview")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:transfer')")
    public ResponseEntity<?> previewEmployeeAssignment(
            @PathVariable String id,
            @Valid @RequestBody EmployeeTransfer r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        return result(assignment.preview(a, id(id), r), 200);
    }

    @PutMapping("/api/v1/admin/employees/{id}/assignment")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:transfer')")
    public ResponseEntity<?> replaceEmployeeAssignment(
            @PathVariable String id,
            @Valid @RequestBody Commit r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        return result(assignment.commit(a, id(id), r), 200);
    }

    @GetMapping("/api/v1/auth/effective-permissions")
    @AuthenticatedEndpoint
    public ResponseEntity<?> getOwnEffectivePermissions(
            @RequestParam() String applicationId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        Actor a = actor(p, request);
        return result(service.ownEffective(a, id(applicationId)), 200);
    }
}

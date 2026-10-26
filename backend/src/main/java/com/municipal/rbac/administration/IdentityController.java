/** 功能：身份管理HTTP适配；对应唯一OpenAPI，操作者来自服务端主体，每个方法显式方法授权。 */
package com.municipal.rbac.administration;

import static com.municipal.rbac.identity.IdentityData.id;

import com.municipal.rbac.security.IdentityPrincipal;
import com.municipal.rbac.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class IdentityController {
    private final IdentityAdministration service;

    public IdentityController(IdentityAdministration service) {
        this.service = service;
    }

    static IdentityAdministration.Actor actor(IdentityPrincipal p, HttpServletRequest request) {
        return new IdentityAdministration.Actor(
                p.id(),
                p.authVersion(),
                request.getSession(false).getId(),
                String.valueOf(request.getAttribute("traceId")));
    }

    private ResponseEntity<?> result(Object value, int status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(value));
    }

    @GetMapping("/organizations")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:view')")
    public ResponseEntity<?> organizations(
            @AuthenticationPrincipal IdentityPrincipal p, HttpServletRequest request) {
        return result(Map.of("items", service.organizations(actor(p, request))), 200);
    }

    @GetMapping("/organizations/{id}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:view')")
    public ResponseEntity<?> organization(
            @PathVariable String id,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.organization(actor(p, request), id(id)), 200);
    }

    @PostMapping("/organizations")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:create')")
    public ResponseEntity<?> createOrganization(
            @Valid @RequestBody IdentityRequests.OrganizationCreate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.createOrganization(actor(p, request), r), 201);
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:update')")
    public ResponseEntity<?> updateOrganization(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.OrganizationUpdate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.updateOrganization(actor(p, request), id(id), r), 200);
    }

    @PostMapping("/organizations/{id}/status")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:status')")
    public ResponseEntity<?> organizationStatus(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.Status r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.organizationStatus(actor(p, request), id(id), r), 200);
    }

    @PostMapping("/organizations/{id}/move-preview")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:move')")
    public ResponseEntity<?> preview(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.MovePreview r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.preview(actor(p, request), id(id), r), 200);
    }

    @PostMapping("/organizations/{id}/move")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:organization:move')")
    public ResponseEntity<?> move(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.MoveCommit r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.move(actor(p, request), id(id), r), 200);
    }

    @GetMapping("/employees")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:view')")
    public ResponseEntity<?> employees(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "false") boolean includeDescendants,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(
                service.employees(
                        actor(p, request),
                        page,
                        size,
                        q,
                        sort,
                        enabled,
                        organizationId == null ? null : id(organizationId),
                        includeDescendants),
                200);
    }

    @GetMapping("/employees/{id}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:view')")
    public ResponseEntity<?> employee(
            @PathVariable String id,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.employee(actor(p, request), id(id)), 200);
    }

    @PostMapping("/employees")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:create')")
    public ResponseEntity<?> createEmployee(
            @Valid @RequestBody IdentityRequests.EmployeeCreate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.createEmployee(actor(p, request), r), 201);
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:update')")
    public ResponseEntity<?> updateEmployee(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.EmployeeUpdate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.updateEmployee(actor(p, request), id(id), r), 200);
    }

    @PostMapping("/employees/{id}/status")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:employee:status')")
    public ResponseEntity<?> employeeStatus(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.Status r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.employeeStatus(actor(p, request), id(id), r), 200);
    }

    @GetMapping("/accounts")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:view')")
    public ResponseEntity<?> accounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) Boolean enabled,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.accounts(actor(p, request), page, size, q, sort, enabled), 200);
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:view')")
    public ResponseEntity<?> account(
            @PathVariable String id,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.account(actor(p, request), id(id)), 200);
    }

    @GetMapping("/employees/{id}/account")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:view')")
    public ResponseEntity<?> employeeAccount(
            @PathVariable String id,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.employeeAccount(actor(p, request), id(id)), 200);
    }

    @PostMapping("/accounts")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:create')")
    public ResponseEntity<?> createAccount(
            @Valid @RequestBody IdentityRequests.AccountCreate r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.createAccount(actor(p, request), r), 201);
    }

    @PostMapping("/accounts/{id}/status")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:status')")
    public ResponseEntity<?> accountStatus(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.Status r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.accountStatus(actor(p, request), id(id), r), 200);
    }

    @PostMapping("/accounts/{id}/password-reset")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:account:reset-password')")
    public ResponseEntity<?> reset(
            @PathVariable String id,
            @Valid @RequestBody IdentityRequests.PasswordReset r,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.resetPassword(actor(p, request), id(id), r), 200);
    }

    @GetMapping("/audit")
    @PreAuthorize("@permissionBridge.platform(authentication, 'admin:audit:view')")
    public ResponseEntity<?> audit(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest request) {
        return result(service.audit(actor(p, request), page, size), 200);
    }
}

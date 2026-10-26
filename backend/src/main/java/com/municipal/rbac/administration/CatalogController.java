/** 功能：资源目录HTTP适配；平台写与应用只读分离，命令及启停预览由CatalogManager实现。 */
package com.municipal.rbac.administration;

import static com.municipal.rbac.identity.IdentityData.id;

import com.municipal.rbac.authorization.AccessData.*;
import com.municipal.rbac.authorization.CatalogData.*;
import com.municipal.rbac.authorization.CatalogManager;
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
public class CatalogController {
    private final CatalogManager service;

    public CatalogController(CatalogManager service) {
        this.service = service;
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

    @GetMapping("/api/v1/admin/catalog/applications")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal IdentityPrincipal p, HttpServletRequest r) {
        return result(Map.of("items", service.applications(actor(p, r))), 200);
    }

    @PostMapping("/api/v1/admin/catalog/applications")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> createApplication(
            @Valid @RequestBody ApplicationCreate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.createApplication(actor(p, r), body), 201);
    }

    @PutMapping("/api/v1/admin/catalog/applications/{applicationId}")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> updateApplication(
            @PathVariable String applicationId,
            @Valid @RequestBody ApplicationUpdate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.updateApplication(actor(p, r), id(applicationId), body), 200);
    }

    @GetMapping("/api/v1/admin/applications/{applicationId}/catalog")
    @PreAuthorize(
            "@permissionBridge.platform(authentication,'admin:catalog:manage') or @permissionBridge.managed(authentication,#applicationId,'admin:role:view')")
    public ResponseEntity<?> catalog(
            @PathVariable String applicationId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.snapshot(actor(p, r), id(applicationId)), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/resources")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> createResource(
            @PathVariable String applicationId,
            @Valid @RequestBody ResourceCreate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.createResource(actor(p, r), id(applicationId), body), 201);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/resources/{resourceId}")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> updateResource(
            @PathVariable String applicationId,
            @PathVariable String resourceId,
            @Valid @RequestBody ResourceUpdate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.updateResource(actor(p, r), id(applicationId), id(resourceId), body), 200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/permissions")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> createPermission(
            @PathVariable String applicationId,
            @Valid @RequestBody PermissionCreate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.createPermission(actor(p, r), id(applicationId), body), 201);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/permissions/{permissionId}")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> updatePermission(
            @PathVariable String applicationId,
            @PathVariable String permissionId,
            @Valid @RequestBody PermissionUpdate body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.updatePermission(actor(p, r), id(applicationId), id(permissionId), body),
                200);
    }

    @PostMapping("/api/v1/admin/catalog/applications/{applicationId}/status-preview")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> previewApplication(
            @PathVariable String applicationId,
            @Valid @RequestBody Status body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.preview(
                        actor(p, r), id(applicationId), "APPLICATION", id(applicationId), body),
                200);
    }

    @PutMapping("/api/v1/admin/catalog/applications/{applicationId}/status")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> statusApplication(
            @PathVariable String applicationId,
            @Valid @RequestBody Commit body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.commit(
                        actor(p, r), id(applicationId), "APPLICATION", id(applicationId), body),
                200);
    }

    @PostMapping("/api/v1/admin/applications/{applicationId}/resources/{resourceId}/status-preview")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> previewResource(
            @PathVariable String applicationId,
            @PathVariable String resourceId,
            @Valid @RequestBody Status body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.preview(actor(p, r), id(applicationId), "RESOURCE", id(resourceId), body),
                200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/resources/{resourceId}/status")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> statusResource(
            @PathVariable String applicationId,
            @PathVariable String resourceId,
            @Valid @RequestBody Commit body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.commit(actor(p, r), id(applicationId), "RESOURCE", id(resourceId), body),
                200);
    }

    @PostMapping(
            "/api/v1/admin/applications/{applicationId}/permissions/{permissionId}/status-preview")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> previewPermission(
            @PathVariable String applicationId,
            @PathVariable String permissionId,
            @Valid @RequestBody Status body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.preview(
                        actor(p, r), id(applicationId), "PERMISSION", id(permissionId), body),
                200);
    }

    @PutMapping("/api/v1/admin/applications/{applicationId}/permissions/{permissionId}/status")
    @PreAuthorize("@permissionBridge.platform(authentication,'admin:catalog:manage')")
    public ResponseEntity<?> statusPermission(
            @PathVariable String applicationId,
            @PathVariable String permissionId,
            @Valid @RequestBody Commit body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.commit(
                        actor(p, r), id(applicationId), "PERMISSION", id(permissionId), body),
                200);
    }
}

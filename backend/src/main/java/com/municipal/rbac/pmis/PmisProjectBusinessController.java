/** 功能：PMIS立项HTTP适配；操作者只取服务端Session，逐方法权限由Spring Security统一执行。 */
package com.municipal.rbac.pmis;

import static com.municipal.rbac.shared.BusinessData.id;

import com.municipal.rbac.authorization.AccessData.Actor;
import com.municipal.rbac.pmis.PmisProjectData.*;
import com.municipal.rbac.security.*;
import com.municipal.rbac.shared.*;
import com.municipal.rbac.shared.BusinessData.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PmisProjectBusinessController {
    private final PmisProjectService service;

    public PmisProjectBusinessController(PmisProjectService service) {
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

    @GetMapping("/api/v1/pmis/context")
    @AuthenticatedEndpoint
    public ResponseEntity<?> context(
            @AuthenticationPrincipal IdentityPrincipal p, HttpServletRequest r) {
        return result(service.context(actor(p, r)), 200);
    }

    @GetMapping("/api/v1/pmis/projects")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:view')")
    public ResponseEntity<?> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean pendingForMe,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.page(actor(p, r), page, size, q, status, mine, pendingForMe), 200);
    }

    @GetMapping("/api/v1/pmis/projects/{recordId}")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:view')")
    public ResponseEntity<?> get(
            @PathVariable String recordId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.get(actor(p, r), id(recordId)), 200);
    }

    @PostMapping("/api/v1/pmis/projects")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:create')")
    public ResponseEntity<?> create(
            @Valid @RequestBody Create body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.create(actor(p, r), body), 201);
    }

    @PutMapping("/api/v1/pmis/projects/{recordId}")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:update')")
    public ResponseEntity<?> update(
            @PathVariable String recordId,
            @Valid @RequestBody Update body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(service.update(actor(p, r), id(recordId), body), 200);
    }

    @GetMapping("/api/v1/pmis/projects/{recordId}/history")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:view')")
    public ResponseEntity<?> history(
            @PathVariable String recordId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(Map.of("items", service.history(actor(p, r), id(recordId))), 200);
    }

    @GetMapping("/api/v1/pmis/projects/{recordId}/approvers")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:submit')")
    public ResponseEntity<?> approvers(
            @PathVariable String recordId,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(Map.of("items", service.approvers(actor(p, r), id(recordId))), 200);
    }

    @PostMapping("/api/v1/pmis/projects/{recordId}/submit")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:submit')")
    public ResponseEntity<?> submit(
            @PathVariable String recordId,
            @Valid @RequestBody Submit body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.act(
                        actor(p, r),
                        id(recordId),
                        "submit",
                        body.expectedVersion(),
                        body.approverUserId(),
                        ""),
                200);
    }

    @PostMapping("/api/v1/pmis/projects/{recordId}/withdraw")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:withdraw')")
    public ResponseEntity<?> withdraw(
            @PathVariable String recordId,
            @Valid @RequestBody Version body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.act(
                        actor(p, r), id(recordId), "withdraw", body.expectedVersion(), null, ""),
                200);
    }

    @PostMapping("/api/v1/pmis/projects/{recordId}/approve")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:approve')")
    public ResponseEntity<?> approve(
            @PathVariable String recordId,
            @Valid @RequestBody Decision body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.act(
                        actor(p, r),
                        id(recordId),
                        "approve",
                        body.expectedVersion(),
                        null,
                        body.comment()),
                200);
    }

    @PostMapping("/api/v1/pmis/projects/{recordId}/reject")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:reject')")
    public ResponseEntity<?> reject(
            @PathVariable String recordId,
            @Valid @RequestBody Decision body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.act(
                        actor(p, r),
                        id(recordId),
                        "reject",
                        body.expectedVersion(),
                        null,
                        body.comment()),
                200);
    }

    @PostMapping("/api/v1/pmis/projects/{recordId}/cancel")
    @PreAuthorize("@permissionBridge.allowed(authentication,'pmis','pmis:project:cancel')")
    public ResponseEntity<?> cancel(
            @PathVariable String recordId,
            @Valid @RequestBody Version body,
            @AuthenticationPrincipal IdentityPrincipal p,
            HttpServletRequest r) {
        return result(
                service.act(actor(p, r), id(recordId), "cancel", body.expectedVersion(), null, ""),
                200);
    }
}

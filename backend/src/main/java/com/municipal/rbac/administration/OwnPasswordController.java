/** 功能：本人改密HTTP适配；事务成功后退出当前会话，身份版本使全部旧会话失效。 */
package com.municipal.rbac.administration;

import com.municipal.rbac.security.*;
import com.municipal.rbac.shared.ApiResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OwnPasswordController {
    private final IdentityAdministration service;

    public OwnPasswordController(IdentityAdministration service) {
        this.service = service;
    }

    @PostMapping("/api/v1/auth/password")
    @AuthenticatedEndpoint
    public ResponseEntity<?> change(
            @Valid @RequestBody IdentityRequests.PasswordChange body,
            @AuthenticationPrincipal IdentityPrincipal principal,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.changePassword(
                IdentityController.actor(principal, request),
                body.currentPassword(),
                body.newPassword());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("SESSION").logout(request, response, authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(Map.of("authenticated", false)));
    }
}

/** 功能：唯一身份契约的请求适配；覆盖写强制版本，字段存在性区分显式null与遗漏。 */
package com.municipal.rbac.administration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public final class IdentityRequests {
    private IdentityRequests() {}

    public record OrganizationCreate(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank String nodeType,
            @JsonProperty(required = true) String parentId) {}

    public record OrganizationUpdate(
            @NotBlank @Size(max = 160) String name,
            @JsonProperty(required = true) String managerEmployeeId,
            @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion) {}

    public record Status(
            @NotNull Boolean enabled,
            @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion) {}

    public record MovePreview(
            @NotBlank String parentId,
            @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion) {}

    public record MoveCommit(@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String previewToken) {}

    public record EmployeeCreate(
            @NotBlank @Size(max = 64) String employeeCode,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank String organizationId,
            @JsonProperty(required = true) @Size(max = 100) String positionName,
            @JsonProperty(required = true) @Size(max = 32) String phone) {}

    public record EmployeeUpdate(
            @NotBlank @Size(max = 100) String displayName,
            @JsonProperty(required = true) @Size(max = 32) String phone,
            @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion) {}

    public record AccountCreate(
            @NotBlank @Size(max = 64) String username, @NotBlank String employeeId) {}

    public record PasswordReset(
            @NotNull @PositiveOrZero @Max(9007199254740991L) Long expectedVersion) {}

    public record PasswordChange(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {
        @Override
        public String toString() {
            return "PasswordChange[redacted]";
        }
    }
}

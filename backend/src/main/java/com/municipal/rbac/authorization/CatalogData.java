/** 功能：资源目录公开命令/视图；由CatalogController适配唯一OpenAPI，CatalogManager负责规则和预览。 */
package com.municipal.rbac.authorization;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class CatalogData {
    private CatalogData() {}

    public record Application(
            String id,
            String code,
            String name,
            String description,
            boolean enabled,
            long version) {}

    public record ApplicationCreate(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{2,31}") String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 500) String description) {}

    public record ApplicationUpdate(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 500) String description,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Resource(
            String id,
            String applicationId,
            String parentId,
            String code,
            String name,
            String kind,
            String description,
            boolean enabled,
            long version) {}

    public record ResourceCreate(
            String parentId,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{2,63}") String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank String kind,
            @NotNull @Size(max = 500) String description) {}

    public record ResourceUpdate(
            String parentId,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 500) String description,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Permission(
            String id,
            String applicationId,
            String resourceId,
            String code,
            String action,
            String name,
            String description,
            boolean enabled,
            boolean effectiveEnabled,
            String implementationStatus,
            long version) {}

    public record PermissionCreate(
            @NotBlank String resourceId,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{2,19}") String action,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 500) String description) {}

    public record PermissionUpdate(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(max = 500) String description,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Snapshot(
            Application application,
            List<Resource> resources,
            List<Permission> permissions,
            List<String> implementedCodes) {}

    public record StatusPreview(
            String token,
            String kind,
            String targetId,
            String applicationId,
            long targetVersion,
            long namespaceVersion,
            long treeVersion,
            boolean enabled,
            long affectedRoles,
            long affectedUsers,
            Instant expiresAt) {}
}

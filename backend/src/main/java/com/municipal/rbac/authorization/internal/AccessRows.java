/** 功能：授权持久化内部记录；与AccessMapper列顺序及V3迁移一一对应。 */
package com.municipal.rbac.authorization.internal;

import java.time.Instant;

public final class AccessRows {
    private AccessRows() {}

    public record App(long id, String code, String name, boolean enabled, long version) {}

    public record User(
            long id,
            String username,
            Long employeeId,
            Long organizationId,
            boolean enabled,
            boolean employeeEnabled,
            boolean organizationEnabled,
            long authVersion,
            long version) {}

    public record PersonRow(
            String userId,
            String username,
            String employeeId,
            String displayName,
            String organizationId,
            boolean enabled,
            long grantVersion) {}

    public record RoleRow(
            long id,
            Long applicationId,
            String code,
            String name,
            String kind,
            Long ownerUserId,
            boolean personnelAll,
            boolean enabled,
            long version,
            long memberCount) {}

    public record Node(String organizationId, boolean includeDescendants) {}

    public record Link(
            String roleId,
            String permissionId,
            String code,
            String scopeKind,
            Instant userFrom,
            Instant userTo,
            Instant roleFrom,
            Instant roleTo,
            String grantedByUserId,
            boolean legacy) {}

    public record DelegateRow(
            long id,
            long applicationId,
            long administratorUserId,
            boolean enabled,
            Instant validFrom,
            Instant validTo,
            boolean peopleAll,
            long version) {}

    public record PermissionRow(
            String permissionId, String scopeKind, Instant validFrom, Instant validTo) {}

    public record PreviewRow(
            String token,
            long actorUserId,
            String kind,
            long targetId,
            Long applicationId,
            long targetVersion,
            long treeVersion,
            String namespaceJson,
            String payloadJson,
            Instant expiresAt,
            boolean consumed) {}
}

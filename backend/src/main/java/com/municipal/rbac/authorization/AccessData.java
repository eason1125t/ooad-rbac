/** 功能：授权模块公开命令/视图；供管理适配、范围算法和MyBatis使用，不引用HTTP或安全框架。 */
package com.municipal.rbac.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class AccessData {
    public record OrganizationChoice(
            String id,
            String code,
            String name,
            String parentId,
            String nodeType,
            boolean enabled,
            boolean peopleSelectable,
            boolean peopleSubtreeAllowed) {}

    private AccessData() {}

    public record Actor(long id, long authVersion, String sessionId, String traceId) {
        @Override
        public String toString() {
            return "Actor[" + id + "]";
        }
    }

    public record Scope(
            @NotBlank String kind,
            @NotNull @Size(max = 100) List<@NotBlank String> organizationIds) {}

    public record OrganizationRule(
            @NotBlank String organizationId, @NotNull Boolean includeDescendants) {}

    public record PeopleScope(
            @NotNull Boolean allCompany,
            @NotNull @Size(max = 100) List<@Valid OrganizationRule> organizations) {}

    public record PermissionGrant(
            @NotBlank String permissionId,
            @NotNull @Valid Scope scope,
            Instant validFrom,
            Instant validTo) {}

    public record Permission(
            String id,
            String applicationId,
            String code,
            String name,
            boolean enabled,
            String implementationStatus) {}

    public record PermissionView(
            Permission permission, Scope scope, Instant validFrom, Instant validTo) {}

    public record RoleCreate(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank String kind,
            @JsonProperty(required = true) String ownerUserId,
            @NotNull @Valid PeopleScope personnelScope) {}

    public record RoleUpdate(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid PeopleScope personnelScope,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Status(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Role(
            String id,
            String applicationId,
            String code,
            String name,
            String kind,
            String ownerUserId,
            boolean enabled,
            long version,
            PeopleScope personnelScope,
            boolean canEdit,
            boolean canAssign,
            long memberCount) {}

    public record RolePermissions(String roleId, long version, List<PermissionView> permissions) {}

    public record PermissionSet(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = 2000) List<@Valid PermissionGrant> permissions) {}

    public record Preview(
            String token,
            String kind,
            String targetId,
            String applicationId,
            long targetVersion,
            long treeVersion,
            Map<String, Long> namespaceVersions,
            long affectedUsers,
            Instant expiresAt,
            Map<String, ?> beforeState,
            Map<String, ?> afterState) {}

    public record Commit(@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String previewToken) {}

    public record RoleOwner(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank String newAdministratorUserId) {}

    public record MembershipInput(@NotBlank String roleId, Instant validFrom, Instant validTo) {}

    public record Membership(
            String roleId,
            String roleName,
            String kind,
            boolean enabled,
            Instant validFrom,
            Instant validTo,
            String grantedByUserId,
            Instant grantedAt) {}

    public record UserRoleSet(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = 100) List<@Valid MembershipInput> memberships) {}

    public record Responsibility(
            String applicationId, String userId, String delegationId, String state, long version) {}

    public record ResponsibilitySet(
            @JsonProperty(required = true) String delegationId,
            @JsonProperty(required = true) @PositiveOrZero Long expectedVersion) {}

    public record UserRoles(
            String applicationId,
            String userId,
            long version,
            List<Membership> memberships,
            Responsibility responsibility) {}

    public record MemberInput(
            @NotBlank String userId,
            @NotNull @PositiveOrZero Long expectedVersion,
            Instant validFrom,
            Instant validTo) {}

    public record MemberBatch(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(min = 1, max = 100) List<@Valid MemberInput> members) {}

    public record MemberRemoval(
            @NotBlank String userId,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @PositiveOrZero Long expectedUserVersion) {}

    public record UserVersion(String userId, long version) {}

    public record BatchResult(String roleId, long affectedUsers, List<UserVersion> users) {}

    public record Person(
            String userId,
            String username,
            String employeeId,
            String displayName,
            String organizationId,
            boolean enabled,
            long grantVersion,
            Responsibility responsibility) {}

    public record RoleMember(
            String userId,
            String username,
            String roleId,
            Instant validFrom,
            Instant validTo,
            String grantedByUserId,
            Instant grantedAt,
            long grantVersion) {}

    public record Ceiling(@NotBlank String permissionId, @NotNull @Valid Scope scope) {}

    public record DelegationSet(
            @NotBlank String administratorUserId,
            @NotNull Boolean enabled,
            Instant validFrom,
            Instant validTo,
            @NotNull @Valid PeopleScope peopleScope,
            @NotNull @Size(max = 2000) List<@NotBlank String> commonRoleIds,
            @NotNull @Size(max = 2000) List<@Valid Ceiling> permissionCeilings,
            @JsonProperty(required = true) @PositiveOrZero Long expectedVersion) {}

    public record Delegation(
            String id,
            String applicationId,
            String administratorUserId,
            boolean enabled,
            Instant validFrom,
            Instant validTo,
            PeopleScope peopleScope,
            List<String> commonRoleIds,
            List<Ceiling> permissionCeilings,
            long version) {}

    public record Capabilities(
            boolean all, boolean self, Set<String> organizationIds, Set<String> subtreeRootIds) {}

    public record EffectivePermission(
            String permissionId, String code, Capabilities capabilities) {}

    public record EffectiveSource(
            String roleId,
            String permissionId,
            String code,
            Scope scope,
            Instant validFrom,
            Instant validTo,
            String grantedByUserId,
            boolean legacy) {}

    public record Effective(
            String userId,
            String applicationId,
            List<EffectivePermission> permissions,
            List<EffectiveSource> sources) {}

    public record DelegablePermission(String permissionId, Capabilities capabilities) {}

    public record Application(
            String id,
            String code,
            String name,
            long version,
            boolean platform,
            Delegation delegation,
            List<String> allowedOperations,
            List<DelegablePermission> delegablePermissions) {}

    public record TransferApplication(
            @NotBlank String applicationId,
            @NotNull @Size(max = 100) List<@Valid MembershipInput> memberships,
            @JsonProperty(required = true) String responsibilityDelegationId) {}

    public record EmployeeTransfer(
            @NotNull @PositiveOrZero Long expectedVersion,
            @JsonProperty(required = true) @PositiveOrZero Long expectedAccountVersion,
            @NotBlank String organizationId,
            @JsonProperty(required = true) @Size(max = 100) String positionName,
            @NotBlank String managementAction,
            @NotNull @Size(max = 100) List<@Valid TransferApplication> applications) {}
}

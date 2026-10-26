/** 功能：一次JOIN读取主体的全部启用授权来源；时间在Resolver用Clock判断，SQL不拼接或N+1。 */
package com.municipal.rbac.authorization.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GrantMapper {
    String ACTIVE_RESOURCE =
            "(p.resource_id IS NULL OR NOT EXISTS(SELECT 1 FROM auth_resource_path cr JOIN auth_resource ar ON ar.id=cr.ancestor_id WHERE cr.application_id=p.application_id AND cr.descendant_id=p.resource_id AND ar.enabled=FALSE))";
    String SOURCE_JOINS =
            """
        FROM auth_user_role ur
        JOIN id_user u ON u.id=ur.user_id AND u.enabled=TRUE
        LEFT JOIN id_employee e ON e.id=u.employee_id
        JOIN auth_role r ON r.id=ur.role_id AND r.enabled=TRUE
        JOIN auth_role_permission rp ON rp.role_id=r.id
        JOIN auth_permission p ON p.id=rp.permission_id AND p.enabled=TRUE AND p.implementation_status='IMPLEMENTED'
        JOIN auth_application a ON a.id=p.application_id AND a.enabled=TRUE
        """;
    String SOURCE_CONDITIONS =
            """
        AND p.code LIKE CONCAT(a.code,':%')
        AND (r.kind='LEGACY' OR r.application_id=p.application_id)
        AND (e.id IS NULL OR (e.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM id_organization_path z JOIN id_organization o ON o.id=z.ancestor_id WHERE z.descendant_id=e.organization_id AND o.enabled=FALSE)))
        AND (r.kind<>'SCOPED' OR r.personnel_all=TRUE OR EXISTS(SELECT 1 FROM auth_role_organization n JOIN id_organization_path z ON z.ancestor_id=n.organization_id AND z.descendant_id=e.organization_id AND (n.include_descendants=TRUE OR z.depth=0) WHERE n.role_id=r.id))
        """
                    + " AND "
                    + ACTIVE_RESOURCE;
    String SOURCE = SOURCE_JOINS + " WHERE ur.user_id=#{subjectId} " + SOURCE_CONDITIONS;

    @Select(
            """
        SELECT p.code, ur.valid_from AS user_from, ur.valid_to AS user_to,
               rp.valid_from AS role_from, rp.valid_to AS role_to
        """
                    + SOURCE)
    List<Grant> findSources(long subjectId);

    @Select(
            "SELECT r.id role_id,p.id permission_id,p.code,rp.scope_kind,ur.valid_from user_from,ur.valid_to user_to,rp.valid_from role_from,rp.valid_to role_to,ur.granted_by granted_by_user_id,(r.kind='LEGACY') legacy "
                    + SOURCE
                    + " AND p.application_id=#{app}")
    List<AccessRows.Link> scopeSources(@Param("subjectId") long subjectId, @Param("app") long app);

    @Select(
            "SELECT d.application_id FROM auth_delegation d JOIN auth_delegation_action da ON da.delegation_id=d.id JOIN auth_permission p ON p.id=da.permission_id AND p.enabled=TRUE JOIN auth_application a ON a.id=d.application_id AND a.enabled=TRUE JOIN id_user u ON u.id=d.administrator_user_id AND u.enabled=TRUE LEFT JOIN id_employee e ON e.id=u.employee_id WHERE u.id=#{subjectId} AND p.code=#{operation} AND d.enabled=TRUE AND (e.id IS NULL OR (e.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM id_organization_path z JOIN id_organization o ON o.id=z.ancestor_id WHERE z.descendant_id=e.organization_id AND o.enabled=FALSE))) AND (d.valid_from IS NULL OR d.valid_from<=#{now}) AND (d.valid_to IS NULL OR d.valid_to>#{now})")
    Set<Long> managedApplications(
            @Param("subjectId") long subjectId,
            @Param("operation") String operation,
            @Param("now") Instant now);
}

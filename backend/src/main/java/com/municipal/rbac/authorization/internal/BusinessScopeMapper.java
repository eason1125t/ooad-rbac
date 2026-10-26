/** 功能：公开业务授权服务的组织展开和审批候选SQL；来源/时间/目录/范围均在分页截断前核验。 */
package com.municipal.rbac.authorization.internal;

import com.municipal.rbac.authorization.BusinessAccess.Approver;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BusinessScopeMapper {
    @Select(
            "<script>SELECT DISTINCT descendant_id FROM id_organization_path WHERE <choose><when test='exact.size > 0 or roots.size > 0'><trim prefixOverrides='OR'><if test='exact.size > 0'> OR (depth=0 AND ancestor_id IN <foreach collection='exact' item='n' open='(' separator=',' close=')'>#{n}</foreach>)</if><if test='roots.size > 0'> OR ancestor_id IN <foreach collection='roots' item='n' open='(' separator=',' close=')'>#{n}</foreach></if></trim></when><otherwise>FALSE</otherwise></choose></script>")
    Set<Long> organizations(@Param("exact") Set<String> exact, @Param("roots") Set<String> roots);

    String ELIGIBLE =
            " AND r.kind<>'LEGACY' AND p.application_id=#{app} AND (ur.valid_from IS NULL OR ur.valid_from<=#{now}) AND (ur.valid_to IS NULL OR ur.valid_to>#{now}) AND (rp.valid_from IS NULL OR rp.valid_from<=#{now}) AND (rp.valid_to IS NULL OR rp.valid_to>#{now}) AND (rp.scope_kind='ALL' OR (rp.scope_kind='SELF' AND u.id=#{owner}) OR EXISTS(SELECT 1 FROM auth_role_permission_organization n JOIN id_organization_path z ON z.ancestor_id=n.organization_id AND z.descendant_id=#{org} AND (rp.scope_kind='ORG_SUBTREE' OR (rp.scope_kind='ORG' AND z.depth=0)) WHERE n.role_id=r.id AND n.permission_id=p.id))";
    String SOURCES =
            GrantMapper.SOURCE_JOINS
                    + " WHERE ur.user_id=c.id "
                    + GrantMapper.SOURCE_CONDITIONS
                    + ELIGIBLE;

    @Select(
            "SELECT c.id,c.username FROM id_user c WHERE c.id<>#{owner} AND EXISTS(SELECT 1 "
                    + SOURCES
                    + " AND p.code=#{view}) AND EXISTS(SELECT 1 "
                    + SOURCES
                    + " AND p.code=#{approve}) ORDER BY c.id LIMIT 100")
    List<Approver> approvers(
            @Param("app") long app,
            @Param("owner") long owner,
            @Param("org") long org,
            @Param("view") String view,
            @Param("approve") String approve,
            @Param("now") Instant now);
}

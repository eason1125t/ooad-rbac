/** 功能：一次JOIN读取主体的全部启用授权来源；时间在Resolver用Clock判断，SQL不拼接或N+1。 */
package com.municipal.rbac.authorization.internal;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GrantMapper {
    @Select(
            """
        SELECT p.code, ur.valid_from AS user_from, ur.valid_to AS user_to,
               rp.valid_from AS role_from, rp.valid_to AS role_to
        FROM auth_user_role ur
        JOIN id_user u ON u.id = ur.user_id AND u.enabled = TRUE
        JOIN auth_role r ON r.id = ur.role_id AND r.enabled = TRUE
        JOIN auth_role_permission rp ON rp.role_id = r.id
        JOIN auth_permission p ON p.id = rp.permission_id AND p.enabled = TRUE
        JOIN auth_application a ON a.id = p.application_id AND a.enabled = TRUE
        WHERE ur.user_id = #{subjectId} AND p.code LIKE CONCAT(a.code, ':%')
        """)
    List<Grant> findSources(long subjectId);
}

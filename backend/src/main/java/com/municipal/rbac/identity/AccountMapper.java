/** 功能：按唯一账号或ID读取身份状态；供认证和每请求账号撤销检查使用，仅访问身份表。 */
package com.municipal.rbac.identity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {
    @Select(
            "SELECT id, username, password_hash, enabled, auth_version, force_password_change FROM id_user WHERE username = #{username}")
    Account byUsername(String username);

    @Select(
            "SELECT id, username, password_hash, enabled, auth_version, force_password_change FROM id_user WHERE id = #{id}")
    Account byId(long id);
}

/** 功能：组合根的专用引导事务，初始化账号及角色关系；业务模块不共享其Mapper，不读取原始Excel。 */
package com.municipal.rbac.bootstrap;

import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminInitializer {
    private final InitializerMapper mapper;

    public AdminInitializer(InitializerMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void initialize(String username, String hash) {
        // 重启时已有账号不重设密码、不隐式恢复权限；只有新建账号才赋予引导角色。
        if (mapper.insert(username, hash) == 1) mapper.assign(username);
    }

    @Mapper
    public interface InitializerMapper {
        @Insert(
                "INSERT INTO id_user (username,password_hash) SELECT #{username},#{hash} WHERE NOT EXISTS (SELECT 1 FROM id_user WHERE username=#{username})")
        int insert(@Param("username") String username, @Param("hash") String hash);

        @Insert(
                "INSERT INTO auth_user_role (user_id,role_id) SELECT id,1 FROM id_user WHERE username=#{username}")
        int assign(String username);
    }
}

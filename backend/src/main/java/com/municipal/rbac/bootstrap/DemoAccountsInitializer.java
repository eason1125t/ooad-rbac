/** 功能：显式demo配置下创建合成账号/演示授权；与V1目录配合，不读取员工附件、不提供管理API。 */
package com.municipal.rbac.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoAccountsInitializer implements ApplicationRunner {
    private final DemoMapper mapper;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final Environment environment;
    private final String retiredUsername;

    public DemoAccountsInitializer(
            DemoMapper mapper,
            PasswordEncoder passwords,
            ObjectMapper json,
            Environment environment,
            @Value("${app.demo.retire-bootstrap-username:}") String retiredUsername) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.json = json;
        this.environment = environment;
        this.retiredUsername = retiredUsername;
    }

    public record Role(String code, String name, List<String> permissions) {}

    public record Account(String username, String label, List<String> roles) {}

    public record Catalog(String password, List<Role> roles, List<Account> accounts) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The demo and prod profiles must not be combined");
        }
        try (var input = new ClassPathResource("demo/accounts.json").getInputStream()) {
            Catalog catalog = json.readValue(input, Catalog.class);
            for (Role role : catalog.roles()) {
                if (mapper.createRole(role.code(), role.name()) == 1) {
                    for (String permission : role.permissions())
                        mapper.rolePermission(role.code(), permission);
                }
            }
            for (Account account : catalog.accounts()) {
                // 演示数据仅首次创建：重启不重置口令、不恢复撤销授权、不重新启用已停用账号。
                if (mapper.createUser(account.username(), passwords.encode(catalog.password()))
                        == 1) {
                    for (String role : account.roles()) mapper.userRole(account.username(), role);
                }
            }
        }
        // 仅在本机显式配置旧引导账号时停用它，保留历史；不删除或更改任何员工关联账号。
        if (!retiredUsername.isBlank() && !retiredUsername.equals("super-admin"))
            mapper.retire(retiredUsername);
    }

    @Mapper
    public interface DemoMapper {
        @Insert(
                "INSERT INTO auth_role(code,name) SELECT #{code},#{name} WHERE NOT EXISTS(SELECT 1 FROM auth_role WHERE code=#{code})")
        int createRole(@Param("code") String code, @Param("name") String name);

        @Insert(
                "INSERT INTO auth_role_permission(role_id,permission_id) SELECT r.id,p.id FROM auth_role r JOIN auth_permission p ON p.code=#{permission} WHERE r.code=#{role}")
        int rolePermission(@Param("role") String role, @Param("permission") String permission);

        @Insert(
                "INSERT INTO id_user(username,password_hash) SELECT #{username},#{hash} WHERE NOT EXISTS(SELECT 1 FROM id_user WHERE username=#{username})")
        int createUser(@Param("username") String username, @Param("hash") String hash);

        @Insert(
                "INSERT INTO auth_user_role(user_id,role_id) SELECT u.id,r.id FROM id_user u JOIN auth_role r ON r.code=#{role} WHERE u.username=#{username}")
        int userRole(@Param("username") String username, @Param("role") String role);

        @Update(
                "UPDATE id_user u SET enabled=FALSE,auth_version=auth_version+1 WHERE username=#{username} AND employee_id IS NULL AND enabled=TRUE AND EXISTS(SELECT 1 FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='bootstrap_admin')")
        int retire(String username);
    }
}

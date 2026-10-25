/** 功能：环境引导入口；无配置时不建默认账号，密码合法才交身份初始化事务，不记录口令。 */
package com.municipal.rbac.bootstrap;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final AdminInitializer initializer;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public BootstrapAdmin(
            AdminInitializer initializer,
            PasswordEncoder passwords,
            @Value("${app.bootstrap.username:}") String username,
            @Value("${app.bootstrap.password:}") String password) {
        this.initializer = initializer;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) return;
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}")
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException(
                    "Configure a valid bootstrap username and a 12+ character password (at most 72 UTF-8 bytes)");
        initializer.initialize(username, passwords.encode(password));
    }
}

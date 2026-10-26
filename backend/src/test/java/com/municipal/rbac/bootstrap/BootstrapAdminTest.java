/** 功能：验证正式引导配置、UTF-8口令边界和重启不重新授权；与BootstrapAdmin/AdminInitializer配合，不依赖可选原生MySQL测试。 */
package com.municipal.rbac.bootstrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapAdminTest {
    @Test
    void missingConfigurationNeverCreatesDefaultCredentials() {
        var initializer = mock(AdminInitializer.class);
        var passwords = mock(PasswordEncoder.class);
        new BootstrapAdmin(initializer, passwords, "", "").run(new DefaultApplicationArguments());
        verifyNoInteractions(initializer, passwords);
    }

    @Test
    void partialInvalidOrOversizedConfigurationFailsBeforeHashing() {
        var initializer = mock(AdminInitializer.class);
        var passwords = mock(PasswordEncoder.class);
        String[][] invalid = {
            {"", "isolated-bootstrap-password"},
            {"setup-admin", ""},
            {"Setup-admin", "isolated-bootstrap-password"},
            {"setup-admin", "short"},
            // BCrypt以UTF-8字节而非Java字符数限制输入，25个汉字为75字节。
            {"setup-admin", "界".repeat(25)}
        };
        for (String[] configuration : invalid) {
            var bootstrap =
                    new BootstrapAdmin(initializer, passwords, configuration[0], configuration[1]);
            assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(initializer, passwords);
    }

    @Test
    void validPasswordsIncludingSeventyTwoUtf8BytesAreHashedBeforeInitialization() {
        for (String password : new String[] {"isolated-bootstrap-password", "界".repeat(24)}) {
            var initializer = mock(AdminInitializer.class);
            var passwords = mock(PasswordEncoder.class);
            when(passwords.encode(password)).thenReturn("isolated-encoded-hash");
            new BootstrapAdmin(initializer, passwords, "setup-admin", password)
                    .run(new DefaultApplicationArguments());
            var order = inOrder(passwords, initializer);
            order.verify(passwords).encode(password);
            order.verify(initializer).initialize("setup-admin", "isolated-encoded-hash");
            order.verifyNoMoreInteractions();
        }
    }

    @Test
    void onlyNewAccountsReceiveBootstrapRoleExistingAccountsKeepTheirRevocations() {
        var mapper = mock(AdminInitializer.InitializerMapper.class);
        var initializer = new AdminInitializer(mapper);
        when(mapper.insert("new-admin", "new-hash")).thenReturn(1);
        when(mapper.insert("existing-admin", "replacement-hash")).thenReturn(0);

        initializer.initialize("new-admin", "new-hash");
        initializer.initialize("existing-admin", "replacement-hash");

        verify(mapper).assign("new-admin");
        verify(mapper, never()).assign("existing-admin");
        verify(mapper).insert("existing-admin", "replacement-hash");
    }
}

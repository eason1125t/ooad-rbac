/** 功能：验证简易口令只能由demo入口创建，prod组合及正式引导入口保持拒绝；不使用真实人员数据。 */
package com.municipal.rbac.bootstrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class DemoProfileTest {
    @Test
    void prodAndDemoCannotSeedAccountsTogether() {
        var mapper = mock(DemoAccountsInitializer.DemoMapper.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("demo", "prod");
        var initializer =
                new DemoAccountsInitializer(
                        mapper, mock(PasswordEncoder.class), new ObjectMapper(), environment, "");
        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void normalBootstrapStillRejectsFourCharacterPassword() {
        var initializer = mock(AdminInitializer.class);
        var bootstrap =
                new BootstrapAdmin(initializer, mock(PasswordEncoder.class), "super-admin", "1234");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(initializer);
    }

    @Test
    void existingDemoUsersDoNotRegainRevokedRolesOnRestart() throws Exception {
        var mapper = mock(DemoAccountsInitializer.DemoMapper.class);
        var passwords = mock(PasswordEncoder.class);
        when(passwords.encode("1234")).thenReturn("unused-existing-account-hash");
        var environment = new MockEnvironment();
        environment.setActiveProfiles("demo");
        var initializer =
                new DemoAccountsInitializer(mapper, passwords, new ObjectMapper(), environment, "");
        initializer.run(new DefaultApplicationArguments());
        verify(mapper, never()).userRole(anyString(), anyString());
        verify(mapper, never()).rolePermission(anyString(), anyString());
        verify(mapper, never()).retire(anyString());
    }
}

/** 功能：身份边界策略测试，覆盖ID/规范化/分页/正式临时密码和口令规则；事务正确性由真实集成验证。 */
package com.municipal.rbac.identity;

import static com.municipal.rbac.identity.IdentityData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.municipal.rbac.identity.internal.IdentityMapper;
import com.municipal.rbac.shared.DomainFailure;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class IdentityPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", " 1", "1.0", "9223372036854775808", "1 OR 1=1"})
    void rejectsNonCanonicalIds(String input) {
        assertThatThrownBy(() -> id(input))
                .isInstanceOfSatisfying(
                        DomainFailure.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void normalizesIdentifiersWithoutChangingDisplayText() {
        assertThat(id("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(code(" ＯＡ-ADMIN ")).isEqualTo("oa-admin");
        assertThat(nameKey(" Ｆinance ")).isEqualTo("finance");
        assertThat(text(" Display Name ", 100)).isEqualTo("Display Name");
        assertThat(phone(null)).isNull();
        assertThat(phone(" ")).isNull();
        assertThat(phone("+1 555-0100")).isEqualTo("+1 555-0100");
        assertThat(search("%!_")).isEqualTo("!%!!!_");
        assertThat(offset(2, 20)).isEqualTo(20);
        assertThatThrownBy(() -> code("ab")).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> text(" ", 10)).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> phone("not-a-number")).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> offset(Integer.MAX_VALUE, 100)).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> search("x".repeat(101))).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> version(0, -1)).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> version(1, 0))
                .isInstanceOfSatisfying(
                        DomainFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    private IdentityDirectory directory(
            IdentityMapper data, AccountMapper accounts, PasswordHasher hasher) {
        return new IdentityDirectory(
                data, accounts, hasher, Clock.systemUTC(), new MockEnvironment());
    }

    @Test
    void formalAccountTemporaryPasswordIsRandomAndMustBeChanged() {
        IdentityMapper data = mock(IdentityMapper.class);
        AccountMapper accounts = mock(AccountMapper.class);
        PasswordHasher hasher = mock(PasswordHasher.class);
        when(data.lockEmployee(20))
                .thenReturn(new Employee("20", "emp-2", "Synthetic", "10", null, null, true, 0));
        when(data.organization(10))
                .thenReturn(
                        new Organization(
                                "10", "root", "Root", "HEADQUARTERS", null, null, true, 0));
        when(hasher.encode(anyString())).thenReturn("synthetic-hash");
        when(data.insertAccount(anyMap()))
                .thenAnswer(
                        i -> {
                            Map<String, Object> v = i.getArgument(0);
                            v.put("id", 77L);
                            return 1;
                        });
        when(data.account(77)).thenReturn(new UserAccount("77", "employee-2", "20", true, true, 0));
        var service = directory(data, accounts, hasher);
        var first = service.createAccount("employee-2", 20);
        var second = service.createAccount("employee-2", 20);
        assertThat(first.temporaryPassword()).hasSize(32).isNotEqualTo(second.temporaryPassword());
        assertThat(first.account().forcePasswordChange()).isTrue();
        assertThat(first.toString()).doesNotContain(first.temporaryPassword());
        verify(hasher).encode(first.temporaryPassword());
    }

    @Test
    void wrongOldPasswordReusedPasswordAndUtf8LimitRejectBeforeWriting() {
        IdentityMapper data = mock(IdentityMapper.class);
        AccountMapper accounts = mock(AccountMapper.class);
        PasswordHasher hasher = mock(PasswordHasher.class);
        var service = directory(data, accounts, hasher);
        assertThatThrownBy(() -> service.changePassword(1, "1234", "short"))
                .isInstanceOfSatisfying(
                        DomainFailure.class,
                        e -> assertThat(e.code()).isEqualTo("PASSWORD_POLICY"));
        assertThatThrownBy(() -> service.changePassword(1, "1234", "中".repeat(25)))
                .isInstanceOfSatisfying(
                        DomainFailure.class,
                        e -> assertThat(e.code()).isEqualTo("PASSWORD_POLICY"));
        when(accounts.byId(1)).thenReturn(new Account(1, "employee-2", "synthetic-hash", true, 0));
        assertThatThrownBy(() -> service.changePassword(1, "incorrect", "Synthetic-Password-42"))
                .isInstanceOfSatisfying(
                        DomainFailure.class,
                        e -> assertThat(e.code()).isEqualTo("CURRENT_PASSWORD_INVALID"));
        when(hasher.matches(anyString(), eq("synthetic-hash"))).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.changePassword(
                                        1, "Synthetic-Password-42", "Synthetic-Password-42"))
                .isInstanceOfSatisfying(
                        DomainFailure.class,
                        e -> assertThat(e.code()).isEqualTo("PASSWORD_REUSED"));
        verify(data, never()).password(anyLong(), anyString(), anyBoolean(), anyLong());
    }
}

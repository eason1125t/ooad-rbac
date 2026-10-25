/** 功能：存入Spring Session的最小主体；继承User擦除凭据，保存账号身份版本而非权限快照。 */
package com.municipal.rbac.security;

import com.municipal.rbac.identity.Account;
import java.io.Serial;
import java.util.List;
import org.springframework.security.core.userdetails.User;

public class IdentityPrincipal extends User {
    @Serial private static final long serialVersionUID = 1L;
    private final long id;
    private final long authVersion;

    public IdentityPrincipal(Account account) {
        super(
                account.username(),
                account.passwordHash(),
                account.enabled(),
                true,
                true,
                true,
                List.of());
        id = account.id();
        authVersion = account.authVersion();
    }

    public long id() {
        return id;
    }

    public long authVersion() {
        return authVersion;
    }
}

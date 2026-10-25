/** 功能：仅需登录的方法标记；供运行时规则和覆盖测试读取，不隐含任何业务操作权限。 */
package com.municipal.rbac.security;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedEndpoint {}

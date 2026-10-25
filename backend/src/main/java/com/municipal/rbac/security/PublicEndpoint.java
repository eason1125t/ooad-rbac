/** 功能：公开Controller方法标记；由运行时规则读取，仅显式标记的方法可匿名访问。 */
package com.municipal.rbac.security;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {}

/** 功能：所有异常出口共用的错误契约；traceId由外层请求过滤器生成，不包含秘密或堆栈。 */
package com.municipal.rbac.shared;

public record ApiError(String code, String message, String traceId) {}

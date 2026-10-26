/** 功能：唯一分页响应模型；组织/人员/账号及审计复用，不放入业务规则。 */
package com.municipal.rbac.shared;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {}

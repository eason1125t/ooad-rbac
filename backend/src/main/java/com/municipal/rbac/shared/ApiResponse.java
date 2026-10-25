/** 功能：成功响应公共契约；与contracts/openapi.yaml的data封装一致，不存放业务逻辑。 */
package com.municipal.rbac.shared;

public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}

/** 功能：MVC异常适配；与安全Filter出口相同契约，数据依赖故障503，未知异常不向客户端泄密。 */
package com.municipal.rbac.security;

import com.municipal.rbac.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
        return error(request, 503, "DEPENDENCY_UNAVAILABLE", "必要服务暂不可用");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(HttpServletRequest request) {
        return error(request, 403, "FORBIDDEN", "无访问权限");
    }

    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiError> invalid(HttpServletRequest request) {
        return error(request, 400, "INVALID_REQUEST", "请求参数无效");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletRequest request, Exception exception) {
        return InfrastructureBoundaryFilter.infrastructure(exception)
                ? unavailable(request)
                : error(request, 500, "INTERNAL_ERROR", "请求暂无法完成");
    }

    private ResponseEntity<ApiError> error(
            HttpServletRequest request, int status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(code, message, String.valueOf(request.getAttribute("traceId"))));
    }
}

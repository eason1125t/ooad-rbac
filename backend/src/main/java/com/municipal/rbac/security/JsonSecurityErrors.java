/** 功能：Filter/认证处理器共用错误输出，与ApiError和全局异常适配保持一致。 */
package com.municipal.rbac.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.municipal.rbac.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class JsonSecurityErrors {
    private final ObjectMapper json;

    public JsonSecurityErrors(ObjectMapper json) {
        this.json = json;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(
                response.getOutputStream(),
                new ApiError(code, message, String.valueOf(request.getAttribute("traceId"))));
    }
}

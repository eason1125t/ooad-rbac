/** 功能：按Controller显式规则做运行时默认拒绝；这里只判公开/已登录，具体权限交Spring方法授权。 */
package com.municipal.rbac.security;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class EndpointRules implements AuthorizationManager<RequestAuthorizationContext> {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public EndpointRules(
            @Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        try {
            var handler = mappings.getObject().getHandler(context.getRequest());
            if (handler == null || !(handler.getHandler() instanceof HandlerMethod method))
                return new AuthorizationDecision(false);
            if (AnnotatedElementUtils.hasAnnotation(method.getMethod(), PublicEndpoint.class))
                return new AuthorizationDecision(true);
            boolean declared =
                    AnnotatedElementUtils.hasAnnotation(
                                    method.getMethod(), AuthenticatedEndpoint.class)
                            || AnnotatedElementUtils.hasAnnotation(
                                    method.getMethod(), PreAuthorize.class);
            Authentication current = authentication.get();
            return new AuthorizationDecision(
                    declared
                            && current != null
                            && current.isAuthenticated()
                            && !(current instanceof AnonymousAuthenticationToken));
        } catch (Exception exception) {
            return new AuthorizationDecision(false);
        }
    }
}

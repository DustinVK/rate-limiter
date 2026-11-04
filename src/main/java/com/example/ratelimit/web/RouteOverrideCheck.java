package com.example.ratelimit.web;

import com.example.ratelimit.RateLimitPolicy;
import com.example.ratelimit.RateLimitProperties;
import com.example.ratelimit.RateLimitProperties.RouteOverride;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fails startup if a route override names an endpoint no controller serves,
 * e.g. {@code /export} for {@code /exports} or {@code PSOT}. Such an override
 * would otherwise never match, and the endpoint would silently keep its default.
 */
class RouteOverrideCheck implements ApplicationListener<ContextRefreshedEvent> {

    private final RateLimitProperties props;

    RouteOverrideCheck(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        RequestMappingHandlerMapping mapping = event.getApplicationContext()
                .getBeanProvider(RequestMappingHandlerMapping.class).getIfUnique();
        if (mapping == null) {
            return; // no Spring MVC in this context (config-only tests)
        }
        Set<String> served = new HashSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (RequestMethod m : methods.isEmpty() ? EnumSet.allOf(RequestMethod.class) : methods) {
                info.getPatternValues().forEach(path -> served.add(RateLimitPolicy.endpoint(m.name(), path)));
            }
        }
        for (RouteOverride r : props.routeOverrides()) {
            String endpoint = RateLimitPolicy.endpoint(r.method(), r.path());
            if (!served.contains(endpoint)) {
                throw new IllegalStateException("ratelimit.route-overrides: no controller serves '" + endpoint + "'");
            }
        }
    }
}

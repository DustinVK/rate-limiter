package com.example.ratelimit.web;

import com.example.ratelimit.RateLimitPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * STAND-IN FOR REAL AUTHENTICATION. Resolves the {@code X-Api-Key} header to
 * an org using the config's api-keys map and stores the org on the request.
 *
 * <p>The limiter never trusts a client-supplied org ID: a client that could
 * choose its org could rotate it to get fresh buckets. Requests without a
 * valid key get 401 here and never create limiter state.
 */
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String ORG_ATTRIBUTE = ApiKeyInterceptor.class.getName() + ".org";
    public static final String API_KEY_HEADER = "X-Api-Key";

    private final RateLimitPolicy policy;
    private final JsonMapper json;

    public ApiKeyInterceptor(RateLimitPolicy policy, JsonMapper json) {
        this.policy = policy;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Browsers send CORS preflights without credentials, so they can never
        // carry the key. Spring answers them itself; no controller runs.
        // Async handlers are dispatched again when they complete; check only the
        // original dispatch, or the request is charged twice.
        if (CorsUtils.isPreFlightRequest(request) || request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        Optional<String> org = policy.orgForApiKey(request.getHeader(API_KEY_HEADER));
        if (org.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of(
                    "error", "unauthorized",
                    "message", "Missing or unknown " + API_KEY_HEADER + " header."));
            return false;
        }
        request.setAttribute(ORG_ATTRIBUTE, org.get());
        return true;
    }
}

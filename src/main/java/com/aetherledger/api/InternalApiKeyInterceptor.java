package com.aetherledger.api;

import com.aetherledger.api.dto.ErrorResponse;
import com.aetherledger.config.InternalApiKeyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Enforces an internal API-key guard on all {@code /api/v1/ops/**} endpoints.
 *
 * <p>The interceptor is registered only for {@code /api/v1/ops/**} via
 * {@link com.aetherledger.config.WebMvcConfig}; all other paths are unaffected.
 *
 * <h3>Fail-closed policy</h3>
 * If {@code internal.api-key} is blank (the default when the
 * {@code INTERNAL_API_KEY} environment variable is not set) every request
 * is denied.  This prevents ops endpoints from being accidentally exposed
 * in a misconfigured deployment.
 *
 * <h3>Timing-safe comparison</h3>
 * Key comparison uses {@link MessageDigest#isEqual} so the response time
 * does not vary with the number of matching prefix bytes, preventing
 * byte-by-byte timing side-channel attacks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-Internal-Api-Key";

    private final InternalApiKeyProperties properties;
    private final ObjectMapper             objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        String expectedKey = properties.getApiKey();

        if (expectedKey == null || expectedKey.isBlank()) {
            log.warn("Ops API key is not configured — denying access to {}", request.getRequestURI());
            writeUnauthorized(response, request,
                "Internal API key is not configured on this server");
            return false;
        }

        String providedKey = request.getHeader(HEADER_NAME);

        if (providedKey == null || providedKey.isBlank()) {
            log.debug("Missing {} header on {}", HEADER_NAME, request.getRequestURI());
            writeUnauthorized(response, request,
                "Missing " + HEADER_NAME + " header");
            return false;
        }

        if (!constantTimeEquals(expectedKey, providedKey)) {
            log.warn("Invalid {} supplied for {}", HEADER_NAME, request.getRequestURI());
            writeUnauthorized(response, request,
                "Invalid " + HEADER_NAME);
            return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private void writeUnauthorized(HttpServletResponse response,
                                   HttpServletRequest  request,
                                   String              message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
            HttpStatus.UNAUTHORIZED.value(),
            "UNAUTHORIZED",
            message,
            Instant.now(),
            request.getRequestURI()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

package com.aetherledger.config;

import com.aetherledger.api.InternalApiKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration for AetherLedger.
 *
 * <p>Registers the {@link InternalApiKeyInterceptor} exclusively for the
 * {@code /api/v1/ops/**} path pattern.  All other paths — public ledger
 * endpoints, Swagger UI, and the actuator — are not affected.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final InternalApiKeyInterceptor interceptor;

    public WebMvcConfig(InternalApiKeyInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/ops/**");
    }
}

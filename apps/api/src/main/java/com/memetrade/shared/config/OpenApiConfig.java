package com.memetrade.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The guest OpenAPI is generated from the code (contract-summary.md Q2), versioned in apps/api/openapi/ and compared
 * in CI; the frontend types are generated from it.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI memetradeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MemeTrade guest API").version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(
                                "accessToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")));
    }

    /**
     * The administrative group (apps/api/openapi/openapi-admin.yaml, C8 operations provided by the modules) gets its
     * own title; the guest specification is unchanged.
     */
    @Bean
    public GlobalOpenApiCustomizer administrativeTitle() {
        return openApi -> {
            if (openApi.getPaths() != null
                    && !openApi.getPaths().isEmpty()
                    && openApi.getPaths().keySet().stream().allMatch(path -> path.startsWith("/api/v1/admin/"))) {
                openApi.info(new Info().title("MemeTrade admin API").version("1.0.0"));
            }
        };
    }

    /** Every object schema rejects undeclared fields, as the server does (NFR6.1, BR1.8). */
    @Bean
    public GlobalOpenApiCustomizer strictObjectSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(schema -> {
                if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                    schema.setAdditionalProperties(false);
                }
            });
        };
    }
}

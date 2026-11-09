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
     * own title; the guest specification is unchanged. Since u1-legal-documents the administrative group also holds the
     * public legal documents (L1), which the administrator app reads too.
     */
    @Bean
    public GlobalOpenApiCustomizer administrativeTitle() {
        return openApi -> {
            if (openApi.getPaths() != null
                    && openApi.getPaths().keySet().stream().anyMatch(OpenApiConfig::isAdministrative)
                    && openApi.getPaths().keySet().stream()
                            .allMatch(path -> isAdministrative(path) || path.startsWith(LEGAL_PREFIX))) {
                openApi.info(new Info().title("MemeTrade admin API").version("1.0.0"));
            }
        };
    }

    /** L1, public to both apps (contract-summary.md). */
    public static final String LEGAL_PREFIX = "/api/v1/legal/";

    private static boolean isAdministrative(String path) {
        return path.startsWith("/api/v1/admin/");
    }

    /**
     * u1-legal-documents: a nullable property that points to another schema (for example {@code privacy}, null in a
     * legacy version, BR1.15) is written as {@code oneOf: [$ref, {type: null}]}, the OpenAPI 3.1 form the frontend type
     * generator reads as {@code X | null}; a {@code $ref} next to {@code type: null} would be read as never null.
     */
    @Bean
    public GlobalOpenApiCustomizer nullableReferences() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(schema -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, io.swagger.v3.oas.models.media.Schema<?>> properties =
                        (java.util.Map<String, io.swagger.v3.oas.models.media.Schema<?>>)
                                (java.util.Map<?, ?>) schema.getProperties();
                if (properties != null) {
                    properties.replaceAll((name, property) -> nullableReference(property));
                }
            });
        };
    }

    static io.swagger.v3.oas.models.media.Schema<?> nullableReference(
            io.swagger.v3.oas.models.media.Schema<?> property) {
        if (property.get$ref() == null
                || property.getTypes() == null
                || !property.getTypes().contains("null")) {
            return property;
        }
        io.swagger.v3.oas.models.media.Schema<?> reference =
                new io.swagger.v3.oas.models.media.JsonSchema().$ref(property.get$ref());
        io.swagger.v3.oas.models.media.Schema<?> none = new io.swagger.v3.oas.models.media.JsonSchema()
                .types(new java.util.LinkedHashSet<>(java.util.List.of("null")));
        io.swagger.v3.oas.models.media.Schema<?> nullable =
                new io.swagger.v3.oas.models.media.JsonSchema().oneOf(java.util.List.of(reference, none));
        nullable.setDescription(property.getDescription());
        return nullable;
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

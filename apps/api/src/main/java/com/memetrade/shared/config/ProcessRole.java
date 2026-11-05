package com.memetrade.shared.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import org.springframework.context.annotation.Conditional;

/**
 * U5 (C20, ADR-005, infrastructure-specification.md "Trabalhos por processo"): the process a bean belongs to. The same
 * image runs as the {@code api} process (default) and as the {@code worker} process, chosen by the property
 * {@code memetrade.process} ({@code MEMETRADE_PROCESS}). A bean annotated with this exists only in its process, so a
 * job never runs twice and the worker has no controller, request filter or web secret.
 *
 * <p>Every job that runs on its own (a {@code @Scheduled} method, an {@code ApplicationReadyEvent} listener, a runner,
 * a {@code SmartLifecycle}, a test seed or a load-test component) carries this annotation; {@code ProcessRoleContextIT}
 * fails when one appears without it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ProcessRoleCondition.class)
public @interface ProcessRole {

    /** The property that names the process; absent means {@link Kind#API}. */
    String PROPERTY = "memetrade.process";

    Kind value();

    /** The two processes of C20. */
    enum Kind {
        API,
        WORKER;

        /** The value of {@code memetrade.process} for this process ({@code api} or {@code worker}). */
        public String propertyValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Parses the property; a missing value is {@link #API}.
         *
         * @throws IllegalArgumentException for any value other than {@code api} or {@code worker}
         */
        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return API;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.propertyValue().equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("MEMETRADE_PROCESS must be api or worker");
        }
    }
}

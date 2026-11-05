package com.memetrade.shared.config;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches a {@link ProcessRole} bean only in the process named by {@code memetrade.process} (default {@code api}). */
public class ProcessRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ProcessRole.class.getName());
        if (attributes == null) {
            return true;
        }
        ProcessRole.Kind wanted = (ProcessRole.Kind) attributes.get("value");
        return wanted == current(context.getEnvironment().getProperty(ProcessRole.PROPERTY));
    }

    /** The process of this run; an invalid value is refused by {@link RuntimeSettingsGuard}, here it is the API. */
    static ProcessRole.Kind current(String raw) {
        try {
            return ProcessRole.Kind.parse(raw);
        } catch (IllegalArgumentException invalid) {
            return ProcessRole.Kind.API;
        }
    }
}

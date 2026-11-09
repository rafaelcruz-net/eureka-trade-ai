package com.memetrade.identity.domain.legal;

import java.util.Objects;

/**
 * Environment settings of the activation (entities.md LegalSettings, BR1.1, BR1.6): whether a non-legacy version must
 * be publishable to be activated (on only in production) and the deployment fields of the environment.
 */
public record LegalSettings(boolean requirePublishable, DeploymentFields deployment) {

    public LegalSettings {
        Objects.requireNonNull(deployment, "deployment");
    }

    /** Local default: publishing not required and no deployment field ("a definir"). */
    public static LegalSettings defaults() {
        return new LegalSettings(false, DeploymentFields.empty());
    }
}

package com.memetrade.identity.domain.legal;

/**
 * Fields of the privacy policy that depend on the environment (entities.md DeploymentFields, BR1.6): storage location,
 * international transfer and the e-mail of the data protection officer. A blank value is absent ({@code null}) and is
 * shown as "a definir"; any absent field makes the current version not publishable.
 */
public record DeploymentFields(String storageLocation, String internationalTransfer, String dpoEmail) {

    public DeploymentFields {
        storageLocation = blankToNull(storageLocation);
        internationalTransfer = blankToNull(internationalTransfer);
        dpoEmail = blankToNull(dpoEmail);
    }

    public static DeploymentFields empty() {
        return new DeploymentFields(null, null, null);
    }

    /** All three fields present (BR1.6). */
    public boolean complete() {
        return storageLocation != null && internationalTransfer != null && dpoEmail != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

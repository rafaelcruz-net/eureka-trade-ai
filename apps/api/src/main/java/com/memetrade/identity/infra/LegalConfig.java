package com.memetrade.identity.infra;

import com.memetrade.identity.api.RetentionTableSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * u1-legal-documents: the default retention source of the J5 port and the {@code legalDocuments} health component.
 *
 * <p>The default {@link RetentionTableSource} exists only while no other bean of the type is registered (BR1.8, J5):
 * the module that owns the retention catalog (Privacy, U2) registers its implementation as a component, which is
 * scanned before this configuration loads its bean methods, so its implementation wins and Identity never depends on
 * Privacy.
 */
@Configuration
public class LegalConfig {

    @Bean
    @ConditionalOnMissingBean(RetentionTableSource.class)
    public RetentionTableSource catalogRetentionTableSource(TermsCatalog catalog) {
        return new CatalogRetentionTableSource(catalog);
    }

    /** Bean name {@code legalDocumentsHealthIndicator}: the health component {@code legalDocuments} (NFR8.2). */
    @Bean
    public LegalDocumentsHealthIndicator legalDocumentsHealthIndicator(TermsCatalog catalog) {
        return new LegalDocumentsHealthIndicator(catalog);
    }
}

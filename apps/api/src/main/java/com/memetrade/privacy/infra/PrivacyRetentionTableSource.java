package com.memetrade.privacy.infra;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.api.RetentionTableSource;
import com.memetrade.privacy.domain.RetentionCatalog;
import com.memetrade.privacy.domain.RetentionRule;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * J5 implemented by the Privacy module (u2-retention, BR2.9, F6): the retention table of the privacy policy comes from
 * {@link RetentionCatalog}, in its order. Registered as a component, so the default source of Identity (the table of
 * the legal catalog, {@code @ConditionalOnMissingBean}) is not created. Privacy receives the J7 declarations of the
 * purges only to report how many it holds; it never reads a table of another module.
 */
@Component
public class PrivacyRetentionTableSource implements RetentionTableSource {

    private static final Logger log = LoggerFactory.getLogger(PrivacyRetentionTableSource.class);

    private final List<RetentionItem> items;

    public PrivacyRetentionTableSource(ObjectProvider<RetentionEnforcerDeclaration> declarations) {
        this.items =
                RetentionCatalog.rules().stream().map(RetentionRule::toItem).toList();
        log.info(
                "Retention catalog loaded: rows={}, purgeDeclarations={}",
                items.size(),
                declarations.stream().count());
    }

    @Override
    public List<RetentionItem> items() {
        return items;
    }
}

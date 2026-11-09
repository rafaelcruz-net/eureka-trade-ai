package com.memetrade.identity.infra;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.api.RetentionTableSource;
import java.util.List;

/**
 * Default implementation of J5 (BR1.8): the retention table declared by the effective current version of the legal
 * catalog; empty when that version is legacy and declares none (BR1.15). Registered only when no other module
 * provides a {@link RetentionTableSource} (Privacy, from U2).
 */
public class CatalogRetentionTableSource implements RetentionTableSource {

    private final TermsCatalog catalog;

    public CatalogRetentionTableSource(TermsCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<RetentionItem> items() {
        return catalog.active().current().retention();
    }
}

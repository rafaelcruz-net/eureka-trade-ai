package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.SourceStatus;
import java.util.List;
import java.util.Optional;

/**
 * A page of the scanner list (C2): items of the photo, the cursor of the next page, the total that passes the filters,
 * how many were left out only by an unknown filtered field, how many new tokens wait for "Atualizar lista", and the
 * state of each source.
 */
public record ScannerPage(
        List<ScannerRow> items,
        Optional<String> nextCursor,
        long total,
        List<SourceStatus> sources,
        long excludedByUnknown,
        long pendingNewTokens) {}

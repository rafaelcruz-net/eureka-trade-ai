package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.SourceStatus;
import java.util.List;
import java.util.Optional;

/** A page of the scanner list; excludedByUnknown and pendingNewTokens are always 0 in U1 (no filters, no refresh). */
public record ScannerPage(
        List<ScannerRow> items,
        Optional<String> nextCursor,
        long total,
        List<SourceStatus> sources,
        int excludedByUnknown,
        int pendingNewTokens) {}

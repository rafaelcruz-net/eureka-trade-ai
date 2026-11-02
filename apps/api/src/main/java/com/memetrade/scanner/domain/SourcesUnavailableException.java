package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.util.List;

/**
 * BR1.4: every source FAILED. The list answers {@code 503 SOURCE_UNAVAILABLE} with the state and last update of each
 * source in the {@code sources} extension of the problem; never zeroed or old values without a mark.
 */
public class SourcesUnavailableException extends ApiException {

    private final transient List<SourceStatus> sources;

    public SourcesUnavailableException(List<SourceStatus> sources) {
        super(ErrorCode.SOURCE_UNAVAILABLE, "Every market source is unavailable.");
        this.sources = List.copyOf(sources);
    }

    public List<SourceStatus> sources() {
        return sources;
    }
}

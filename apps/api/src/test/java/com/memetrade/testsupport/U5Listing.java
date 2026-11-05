package com.memetrade.testsupport;

import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.SourceStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Scanner double of the U5 tests (filters of BR4.9): a scripted listing on command, else the real U4 listing. */
public final class U5Listing implements MarketListingQuery {

    private final MarketListingQuery real;
    private final AtomicReference<List<ListedToken>> scripted = new AtomicReference<>();

    public U5Listing(MarketListingQuery real) {
        this.real = real;
    }

    public void tokens(List<ListedToken> tokens) {
        scripted.set(List.copyOf(tokens));
    }

    public void clear() {
        scripted.set(null);
    }

    @Override
    public ListingResult list(ListingRequest request) {
        List<ListedToken> tokens = scripted.get();
        if (tokens == null) {
            return real.list(request);
        }
        return new ListingResult(tokens, false, tokens.size(), 0, 0);
    }

    @Override
    public List<SourceStatus> sources() {
        return real.sources();
    }
}

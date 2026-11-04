/**
 * Public interface of the MarketData module (U1, completed by U4), the only package other modules may use.
 *
 * <ul>
 *   <li>{@link com.memetrade.marketdata.api.MarketDataQuery} (contract C11): reference price, liquidity, the price and
 *       volume window and the state of the sources.
 *   <li>{@link com.memetrade.marketdata.api.MarketListingQuery}: the single query of the scanner list (filters,
 *       counters and pages of a photo).
 *   <li>{@link com.memetrade.marketdata.api.MarketRiskInputs}: batch inputs of the risk indicator.
 *   <li>{@link com.memetrade.marketdata.api.SourceStatusMarks} (contract C18) and
 *       {@link com.memetrade.marketdata.api.MarketCollectionCompleted}, the event after each collection.
 *   <li>{@link com.memetrade.marketdata.api.MarketSourceAdapter} (contract C19): the port of a market source; only
 *       simulated adapters exist in the beta (C-06).
 * </ul>
 *
 * <p>Every market value is a {@link com.memetrade.marketdata.api.MarketValue}: KNOWN with an exact decimal, its source
 * and observation time, or UNKNOWN with a reason; absence is never zero nor {@code null} (docs/adr/ADR-0004).
 */
package com.memetrade.marketdata.api;

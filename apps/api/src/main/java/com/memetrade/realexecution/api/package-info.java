/**
 * Public interface of the REAL execution module (U4 of 261113-trading-real-bot, contract M6): the decision queue, the
 * orders and their states, the cancellation of the orders not sent yet and the reads of the orders. No other module
 * reads its tables ({@code real_decision_request}, {@code real_order}, {@code real_order_attempt},
 * {@code real_liquidation}, {@code real_upstream_health}, {@code real_execution_purge_run}). The module never holds a
 * key: the bot wallet signs only in the TxSigner, through M1.
 */
package com.memetrade.realexecution.api;

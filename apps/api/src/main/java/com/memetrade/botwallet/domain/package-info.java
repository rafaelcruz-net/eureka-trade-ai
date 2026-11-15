/**
 * Domain of the BotWallet module: activation and consent (BR1.x), preferences and pause (BR2.x), balance (BR3.x) and
 * withdrawal (BR4.x). Pure Java with an injected clock: no web, no infrastructure, no network and no floating point;
 * lamports are {@code long} with overflow checks (NFR10.1).
 */
package com.memetrade.botwallet.domain;

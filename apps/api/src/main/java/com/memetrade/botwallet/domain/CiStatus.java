package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (contract addendum "Porta nova CiStatus", BR1.4): the run of the workflow {@code ci} on
 * {@code main} for the exact commit of the image. In production the GitHub; outside the MAINNET and in the tests a
 * configured value. Never throws: any failure is {@link CiResult.Status#UNREACHABLE}.
 */
@FunctionalInterface
public interface CiStatus {

    CiResult check(String commitSha);
}

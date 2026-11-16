package com.memetrade.botwallet.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 261113-trading-real-bot U5 (entities.md GateSnapshot, Q1): the result of every gate of a release, kept in the audit
 * event of the check. {@code ciRunRef} is the id of the run read, only when it was green.
 */
public record GateSnapshot(
        boolean ciGreen,
        String ciRunRef,
        boolean noOpenDivergence,
        boolean killSwitchOff,
        boolean networkMatches,
        CiResult.Status ci) {

    /** The free details of the audit event: booleans, the run id and the status of the CI; never an address. */
    public Map<String, String> details() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("ciGreen", Boolean.toString(ciGreen));
        if (ciRunRef != null) {
            details.put("ciRunRef", ciRunRef);
        }
        details.put("ci", ci.name());
        details.put("noOpenDivergence", Boolean.toString(noOpenDivergence));
        details.put("killSwitchOff", Boolean.toString(killSwitchOff));
        details.put("networkMatches", Boolean.toString(networkMatches));
        return details;
    }
}

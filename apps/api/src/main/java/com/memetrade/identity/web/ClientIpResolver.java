package com.memetrade.identity.web;

import com.memetrade.identity.infra.IdentityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Source IP for the login limiter (security-design.md, Q1): the address of the direct connection. X-Forwarded-For is
 * honoured only when the direct peer is a configured trusted proxy; the local list is empty, so it is ignored.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    @Autowired
    public ClientIpResolver(IdentityProperties properties) {
        this(properties.trustedProxies());
    }

    public ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies = Set.copyOf(trustedProxies);
    }

    public String resolve(HttpServletRequest request) {
        String direct = request.getRemoteAddr();
        if (!trustedProxies.contains(direct)) {
            return direct;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return direct;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return direct;
    }
}

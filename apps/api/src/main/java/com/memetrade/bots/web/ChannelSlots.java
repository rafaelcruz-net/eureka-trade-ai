package com.memetrade.bots.web;

import com.memetrade.bots.domain.TenantScope;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U9 (NFR2.1, security-design.md "Teto de canais por pessoa", review note Q1 of the NFR design): the open live
 * channels of each person (tenant and person of the token). Counting and registering a channel is ONE atomic
 * operation per person (a conditional increment inside {@link ConcurrentHashMap#compute}), so concurrent openings of
 * the same person never exceed the limit. A person without channels has no entry.
 */
final class ChannelSlots {

    private final int limit;
    private final Map<TenantScope, Integer> open = new ConcurrentHashMap<>();

    ChannelSlots(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("the channel limit must be at least 1");
        }
        this.limit = limit;
    }

    /** Takes one slot of {@code person}; {@code false} when the person already holds {@link #limit()} channels. */
    boolean tryAcquire(TenantScope person) {
        boolean[] acquired = {false};
        open.compute(person, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= limit) {
                return count;
            }
            acquired[0] = true;
            return current + 1;
        });
        return acquired[0];
    }

    /** Gives one slot of {@code person} back; the entry disappears with the last one. */
    void release(TenantScope person) {
        open.computeIfPresent(person, (key, count) -> count <= 1 ? null : count - 1);
    }

    int openOf(TenantScope person) {
        return open.getOrDefault(person, 0);
    }

    int limit() {
        return limit;
    }
}

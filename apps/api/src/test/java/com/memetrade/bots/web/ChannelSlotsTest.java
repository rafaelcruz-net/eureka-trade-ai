package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.domain.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 3 of U9 (NFR2.1): the per-person counter of the live channel, atomic and independent per person. */
@Tag("u9")
class ChannelSlotsTest {

    private static TenantScope person() {
        return new TenantScope(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void acquiresUpToTheLimitAndRefusesTheNext() {
        ChannelSlots slots = new ChannelSlots(5);
        TenantScope person = person();

        for (int i = 0; i < 5; i++) {
            assertThat(slots.tryAcquire(person)).isTrue();
        }

        assertThat(slots.tryAcquire(person)).isFalse();
        assertThat(slots.openOf(person)).isEqualTo(5);
    }

    @Test
    void releasingGivesOneSlotBack() {
        ChannelSlots slots = new ChannelSlots(2);
        TenantScope person = person();
        slots.tryAcquire(person);
        slots.tryAcquire(person);

        slots.release(person);

        assertThat(slots.openOf(person)).isEqualTo(1);
        assertThat(slots.tryAcquire(person)).isTrue();
        assertThat(slots.tryAcquire(person)).isFalse();
    }

    @Test
    void releasingWithoutChannelsNeverGoesNegative() {
        ChannelSlots slots = new ChannelSlots(1);
        TenantScope person = person();

        slots.release(person);
        slots.tryAcquire(person);
        slots.release(person);
        slots.release(person);

        assertThat(slots.openOf(person)).isZero();
        assertThat(slots.tryAcquire(person)).isTrue();
    }

    @Test
    void anotherPersonIsNotAffected() {
        ChannelSlots slots = new ChannelSlots(1);
        TenantScope first = person();
        TenantScope sameTenantOtherPerson = new TenantScope(first.tenantId(), UUID.randomUUID());
        slots.tryAcquire(first);

        assertThat(slots.tryAcquire(first)).isFalse();
        assertThat(slots.tryAcquire(sameTenantOtherPerson)).isTrue();
        assertThat(slots.tryAcquire(person())).isTrue();
    }

    @Test
    void concurrentAcquiresNeverExceedTheLimit() throws Exception {
        ChannelSlots slots = new ChannelSlots(5);
        TenantScope person = person();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return slots.tryAcquire(person);
                }));
            }
            start.countDown();
            int acquired = 0;
            for (Future<Boolean> attempt : attempts) {
                acquired += attempt.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }

            assertThat(acquired).isEqualTo(5);
            assertThat(slots.openOf(person)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLimitMustBePositive() {
        assertThatThrownBy(() -> new ChannelSlots(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChannelSlots(3).limit()).isEqualTo(3);
    }
}

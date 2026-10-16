package me.lovelace.loveAuth.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HashingPoolTest {

    @Test
    void neverExceedsConfiguredConcurrency() {
        HashingPool pool = new HashingPool(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(pool.submit(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                Thread.sleep(30);
                running.decrementAndGet();
                return 1;
            }));
        }
        all.forEach(CompletableFuture::join);
        assertTrue(peak.get() <= 2, "peak " + peak.get());
        pool.shutdown();
    }

    @Test
    void exceptionsPropagate() {
        HashingPool pool = new HashingPool(1);
        CompletableFuture<Object> f = pool.submit(() -> { throw new IllegalStateException("x"); });
        assertThrows(java.util.concurrent.CompletionException.class, f::join);
        pool.shutdown();
    }
}

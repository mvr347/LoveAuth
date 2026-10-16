package me.lovelace.loveAuth.security;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs memory-hard password hashing on a small fixed pool. Each Argon2 call allocates tens of MB,
 * so an unbounded scheduler lets a registration flood exhaust the heap; here at most
 * {@code threads} hashes run at once and the rest wait as tiny queued tasks.
 */
public final class HashingPool {

    private final ExecutorService executor;

    public HashingPool(int threads) {
        int n = Math.max(1, Math.min(threads, 4));
        this.executor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "LoveAuth-Hashing");
            t.setDaemon(true);
            return t;
        });
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

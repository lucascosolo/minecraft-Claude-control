package com.lucascosolo.claudecontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * E12: per-chunk work on each chunk's owning region. A chunk not loaded is never scheduled, and is checked again (loaded and owned)
 * inside the task; the caller waits within a budget, after which the fan-out is closed and late tasks do nothing. Work that changes
 * the world runs each change through {@link #open} so nothing is changed after the close and every change is seen by the caller.
 */
final class ChunkFanOut<T> {
    record Result<T>(List<T> values, int notLoaded, int timedOut, int failed, Throwable firstFailure) {}

    private static final Object NOT_LOADED = new Object();
    private final Plugin plugin;
    private final World world;
    private final List<CompletableFuture<Object>> tasks = new ArrayList<>();
    private final Object lock = new Object();
    private boolean closed;
    private int notLoaded;

    ChunkFanOut(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    /** Schedules work for chunk (cx, cz) unless it is not loaded now. */
    void submit(int cx, int cz, Callable<T> work) {
        if (!world.isChunkLoaded(cx, cz)) { notLoaded++; return; }
        CompletableFuture<Object> f = new CompletableFuture<>();
        tasks.add(f);
        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
            if (isClosed()) return;
            try {
                f.complete(world.isChunkLoaded(cx, cz) && Bukkit.isOwnedByCurrentRegion(world, cx, cz) ? work.call() : NOT_LOADED);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
    }

    /** Runs change under the fan-out's lock unless it is closed; false when closed. */
    boolean open(Runnable change) {
        synchronized (lock) {
            if (closed) return false;
            change.run();
            return true;
        }
    }

    private boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    /** Waits up to budgetMs for every task, then closes; a task not done by then counts as timed out. */
    @SuppressWarnings("unchecked")
    Result<T> await(long budgetMs) throws InterruptedException {
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(Math.max(1, budgetMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // counted per task below
        } finally {
            synchronized (lock) { closed = true; }
        }
        List<T> values = new ArrayList<>();
        int late = 0, failed = 0, unloaded = notLoaded;
        Throwable first = null;
        for (CompletableFuture<Object> f : tasks) {
            if (!f.isDone()) { late++; continue; }
            try {
                Object v = f.getNow(null);
                if (v == NOT_LOADED) unloaded++;
                else if (v != null) values.add((T) v);
            } catch (Exception e) {
                failed++;
                if (first == null) first = e.getCause() == null ? e : e.getCause();
            }
        }
        return new Result<>(values, unloaded, late, failed, first);
    }
}

package com.apkrepacker.apk;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs independent, CPU-bound pipeline work (DEX rewriting, signing) across a bounded thread
 * pool. Concurrency is capped so a phone does not hold too many large DEX files in memory at
 * once; a single CPU or a one-item list runs inline with no pool.
 */
final class ParallelWork {

    private ParallelWork() {
    }

    /** Threads to use for {@code itemCount} independent CPU-bound jobs. */
    static int threads(int itemCount) {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(Math.min(cores, 4), itemCount));
    }

    /**
     * Runs {@code tasks} and returns their results in order. If any task throws, the first
     * failure is rethrown (as a {@link PipelineException} when it already is one) after the
     * rest finish. Runs inline when only one thread is warranted.
     */
    static <T> List<T> run(List<? extends Callable<T>> tasks) throws PipelineException {
        int n = threads(tasks.size());
        if (n <= 1 || tasks.size() <= 1) {
            java.util.List<T> out = new java.util.ArrayList<>(tasks.size());
            try {
                for (Callable<T> t : tasks) {
                    out.add(t.call());
                }
            } catch (Exception e) {
                throw asPipeline(e);
            }
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = pool.invokeAll((List<Callable<T>>) tasks);
            java.util.List<T> out = new java.util.ArrayList<>(futures.size());
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException(Stage.TRANSFORM, "Interrupted while processing: "
                    + e.getMessage(), e);
        } catch (ExecutionException e) {
            throw asPipeline(e.getCause() != null ? e.getCause() : e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static PipelineException asPipeline(Throwable t) {
        if (t instanceof PipelineException) {
            return (PipelineException) t;
        }
        return new PipelineException(Stage.TRANSFORM, t.getMessage(), t);
    }
}

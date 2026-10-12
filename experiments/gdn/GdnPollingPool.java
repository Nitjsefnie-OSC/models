package com.integrallis.models.backend.purejava.qwen35;

import java.util.concurrent.Phaser;
import java.util.function.IntConsumer;

/** Benchmark-only persistent workers with the native pool's five-millisecond polling budget. */
final class GdnPollingPool implements AutoCloseable {
  private final Phaser barrier;
  private final Thread[] workers;
  private final long pollNanos = Long.getLong("gdn.pollNanos", 5_000_000L);
  private volatile IntConsumer operation;
  private volatile Throwable failure;
  private volatile boolean closed;

  GdnPollingPool(int threads) {
    barrier = new Phaser(threads);
    workers = new Thread[threads - 1];
    for (int n = 1; n < threads; n++) {
      int index = n;
      workers[n - 1] =
          Thread.ofPlatform()
              .daemon()
              .name("gdn-java-" + index)
              .start(
                  () -> {
                    while (true) {
                      await();
                      boolean done = closed;
                      if (!done) execute(index);
                      await();
                      if (done) return;
                    }
                  });
    }
  }

  private void execute(int index) {
    try {
      operation.accept(index);
    } catch (Throwable t) {
      failure = t;
    }
  }

  private void await() {
    int phase = barrier.arrive();
    long start = System.nanoTime();
    int rounds = 0;
    while (barrier.getPhase() == phase) {
      if (++rounds >= 4000 && (rounds & 63) == 0 && System.nanoTime() - start >= pollNanos) {
        barrier.awaitAdvance(phase);
        return;
      }
      Thread.onSpinWait();
    }
  }

  synchronized void run(IntConsumer work) {
    if (closed) throw new IllegalStateException("closed");
    operation = work;
    failure = null;
    await();
    execute(0);
    await();
    operation = null;
    if (failure != null) throw new IllegalStateException("worker failed", failure);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    await();
    await();
    boolean interrupted = false;
    for (Thread worker : workers)
      while (worker.isAlive())
        try {
          worker.join();
        } catch (InterruptedException e) {
          interrupted = true;
        }
    if (interrupted) Thread.currentThread().interrupt();
  }
}

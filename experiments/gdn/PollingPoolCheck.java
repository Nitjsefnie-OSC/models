package com.integrallis.models.backend.purejava.qwen35;

import java.util.Arrays;
import java.util.concurrent.Executors;

/** Exercise dispatch, failure propagation, concurrent callers, recovery and shutdown. */
public final class PollingPoolCheck {
  public static void main(String[] args) throws Exception {
    for (int threads : new int[] {1, 4, 12}) {
      var pool = new GdnPollingPool(threads);
      try (var callers = Executors.newFixedThreadPool(2)) {
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int caller = 0; caller < 2; caller++)
          futures.add(
              callers.submit(
                  () -> {
                    for (int n = 1; n <= 100; n++) {
                      int expected = n;
                      int[] result = new int[threads];
                      pool.run(i -> result[i] = expected);
                      if (Arrays.stream(result).anyMatch(x -> x != expected))
                        throw new AssertionError("lost work");
                    }
                  }));
        for (var f : futures) f.get();
      }
      boolean failed = false;
      try {
        pool.run(
            i -> {
              if (i == threads - 1) throw new IllegalArgumentException("injected");
            });
      } catch (IllegalStateException e) {
        failed = e.getCause() instanceof IllegalArgumentException;
      }
      if (!failed) throw new AssertionError("worker failure was hidden");
      int[] recovered = new int[threads];
      pool.run(i -> recovered[i] = 1);
      if (Arrays.stream(recovered).sum() != threads) throw new AssertionError("failed recovery");
      pool.close();
      pool.close();
      try {
        pool.run(i -> {});
        throw new AssertionError("accepted after close");
      } catch (IllegalStateException expected) {
      }
      System.out.println("PASS threads=" + threads + " concurrent=200 failure/recovery/close");
    }
  }
}

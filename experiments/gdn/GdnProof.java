package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import com.integrallis.vectors.core.GgufStagePlan;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

/** Isolated experiment. No production dispatch is changed. */
public final class GdnProof {
  record Input(
      float[] q,
      float[] k,
      float[] v,
      float[] g,
      float[] b,
      float[] initial,
      int tokens,
      int kh,
      int vh,
      int kd,
      int vd) {
    static Input capture(Object[] a) {
      return new Input(
          ((float[]) a[0]).clone(),
          ((float[]) a[1]).clone(),
          ((float[]) a[2]).clone(),
          ((float[]) a[3]).clone(),
          ((float[]) a[4]).clone(),
          ((float[]) a[5]).clone(),
          (int) a[7],
          (int) a[8],
          (int) a[9],
          (int) a[10],
          (int) a[11]);
    }
  }

  record Result(float[] state, float[] output) {}

  static final Map<String, NativeKernelLibrary> libraries = new LinkedHashMap<>();
  static final int THREADS = Integer.getInteger("models.native.kernels.threads", 1);
  static final java.util.concurrent.ExecutorService workers =
      java.util.concurrent.Executors.newFixedThreadPool(
          THREADS, Thread.ofPlatform().daemon().factory());
  static final ThreadLocal<Map<Integer, float[][]>> scratch = ThreadLocal.withInitial(HashMap::new);

  private static final class PollingHolder {
    static final GdnPollingPool INSTANCE = new GdnPollingPool(THREADS);
  }

  static long calls;

  static void apply(String arm, Input i, float[] state, float[] out) {
    float[][] w =
        scratch
            .get()
            .computeIfAbsent(
                i.kd * 1024 + i.vd,
                k ->
                    new float[][] {
                      new float[i.kd], new float[i.kd], new float[i.vd], new float[i.vd]
                    });
    switch (arm) {
      case "java" ->
          GatedDeltaNetRecurrence.forwardPrefixInPlace(
              i.q, i.k, i.v, i.g, i.b, state, out, w[0], w[1], w[2], w[3], i.tokens, i.kh, i.vh,
              i.kd, i.vd);
      case "java-head" ->
          HeadMajorRecurrence.forwardPrefixInPlace(
              i.q, i.k, i.v, i.g, i.b, state, out, w[0], w[1], w[2], w[3], i.tokens, i.kh, i.vh,
              i.kd, i.vd);
      case "java-chunk" -> JavaChunked.apply(i, state, out);
      case "java-register" -> RegisterRecurrence.apply(i, state, out, 0, i.vh);
      case "java-tiled" -> RegisterTiledRecurrence.apply(i, state, out, 0, i.vh);
      case "java-tiled-stage" ->
          GgufStagePlan.of(
                  GgufStagePlan.stage(
                      i.vh,
                      (begin, end) -> RegisterTiledRecurrence.apply(i, state, out, begin, end)))
              .execute();
      case "java-tiled-parallel" ->
          PollingHolder.INSTANCE.run(
              worker ->
                  RegisterTiledRecurrence.apply(
                      i, state, out, i.vh * worker / THREADS, i.vh * (worker + 1) / THREADS));
      case "java-register-parallel" ->
          PollingHolder.INSTANCE.run(
              worker ->
                  RegisterRecurrence.apply(
                      i, state, out, i.vh * worker / THREADS, i.vh * (worker + 1) / THREADS));
      case "java-fused" ->
          FusedRecurrence.forwardPrefixInPlace(
              i.q, i.k, i.v, i.g, i.b, state, out, w[0], w[1], w[2], w[3], i.tokens, i.kh, i.vh,
              i.kd, i.vd);
      case "java-parallel" -> {
        var jobs = new ArrayList<java.util.concurrent.Future<?>>();
        for (int worker = 0; worker < THREADS; worker++) {
          int begin = i.vh * worker / THREADS, end = i.vh * (worker + 1) / THREADS;
          jobs.add(
              workers.submit(
                  () -> {
                    float[][] s =
                        scratch
                            .get()
                            .computeIfAbsent(
                                i.kd * 1024 + i.vd,
                                k ->
                                    new float[][] {
                                      new float[i.kd],
                                      new float[i.kd],
                                      new float[i.vd],
                                      new float[i.vd]
                                    });
                    ParallelRecurrence.HEAD_RANGE.set(new int[] {begin, end});
                    ParallelRecurrence.forwardPrefixInPlace(
                        i.q, i.k, i.v, i.g, i.b, state, out, s[0], s[1], s[2], s[3], i.tokens, i.kh,
                        i.vh, i.kd, i.vd);
                  }));
        }
        for (var job : jobs)
          try {
            job.get();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
      }
      case "java-polling" ->
          PollingHolder.INSTANCE.run(
              worker -> {
                float[][] s =
                    scratch
                        .get()
                        .computeIfAbsent(
                            i.kd * 1024 + i.vd,
                            k ->
                                new float[][] {
                                  new float[i.kd], new float[i.kd], new float[i.vd], new float[i.vd]
                                });
                int[] range = ParallelRecurrence.HEAD_RANGE.get();
                range[0] = i.vh * worker / THREADS;
                range[1] = i.vh * (worker + 1) / THREADS;
                ParallelRecurrence.forwardPrefixInPlace(
                    i.q, i.k, i.v, i.g, i.b, state, out, s[0], s[1], s[2], s[3], i.tokens, i.kh,
                    i.vh, i.kd, i.vd);
              });
      default ->
          libraries
              .get(arm)
              .gatedDeltaNetF32(
                  i.q, i.k, i.v, i.g, i.b, state, out, i.tokens, i.kh, i.vh, i.kd, i.vd);
    }
  }

  static Result run(String arm, Input i) {
    float[] state = i.initial.clone(), out = new float[i.tokens * i.vh * i.vd];
    apply(arm, i, state, out);
    return new Result(state, out);
  }

  static String diff(float[] expected, float[] actual) {
    int unequal = 0, first = -1;
    double max = 0;
    for (int n = 0; n < expected.length; n++) {
      if (!Float.isFinite(actual[n])) throw new AssertionError("nonfinite at " + n);
      if (Float.floatToRawIntBits(expected[n]) != Float.floatToRawIntBits(actual[n])) {
        unequal++;
        if (first < 0) first = n;
      }
      max = Math.max(max, Math.abs((double) expected[n] - actual[n]));
    }
    return unequal + ":" + first + ":" + max;
  }

  static List<String> arms() {
    String selected = System.getProperty("gdn.arms");
    if (selected != null) return List.of(selected.split(","));
    var result =
        new ArrayList<>(
            List.of(
                "java", "java-head", "java-fused", "java-parallel", "java-polling", "java-chunk"));
    result.addAll(libraries.keySet());
    return result;
  }

  static boolean requiresExact(String name) {
    return (name.startsWith("java") && !name.equals("java-chunk"))
        || name.startsWith("native-aligned")
        || name.equals("native-shared");
  }

  static void compare(String label, Input i) {
    Result reference = run("java", i);
    for (String arm : arms()) {
      Result r = run(arm, i);
      Result again = run(arm, i);
      if (!Arrays.equals(r.output, again.output) || !Arrays.equals(r.state, again.state))
        throw new AssertionError("nondeterministic recurrence: " + arm);
      System.out.printf(
          Locale.ROOT,
          "tensor\t%s\t%d\t%d\t%d\t%s\t%s\t%s%n",
          label,
          i.tokens,
          i.kd,
          i.vd,
          arm,
          diff(reference.output, r.output),
          diff(reference.state, r.state));
      if (requiresExact(arm)
          && (!Arrays.equals(reference.output, r.output)
              || !Arrays.equals(reference.state, r.state)))
        throw new AssertionError("head-major changed bytes");
    }
  }

  static Input synthetic(int tokens, int d, boolean nonzero) {
    var r = new SplittableRandom(20261011);
    int kh = 4, vh = 8;
    float[] q = random(r, tokens * kh * d, .25f),
        k = random(r, tokens * kh * d, .25f),
        v = random(r, tokens * vh * d, .25f),
        g = new float[tokens * vh],
        b = new float[tokens * vh];
    for (int n = 0; n < g.length; n++) {
      g[n] = -.05f - r.nextFloat() * .2f;
      b[n] = .2f + r.nextFloat() * .7f;
    }
    return new Input(
        q, k, v, g, b, random(r, vh * d * d, nonzero ? .01f : 0), tokens, kh, vh, d, d);
  }

  static float[] random(SplittableRandom r, int length, float scale) {
    float[] a = new float[length];
    for (int n = 0; n < length; n++) a[n] = (r.nextFloat() - .5f) * scale;
    return a;
  }

  static Input slice(Input i, int at, int count, float[] state) {
    return new Input(
        Arrays.copyOfRange(i.q, at * i.kh * i.kd, (at + count) * i.kh * i.kd),
        Arrays.copyOfRange(i.k, at * i.kh * i.kd, (at + count) * i.kh * i.kd),
        Arrays.copyOfRange(i.v, at * i.vh * i.vd, (at + count) * i.vh * i.vd),
        Arrays.copyOfRange(i.g, at * i.vh, (at + count) * i.vh),
        Arrays.copyOfRange(i.b, at * i.vh, (at + count) * i.vh),
        state,
        count,
        i.kh,
        i.vh,
        i.kd,
        i.vd);
  }

  static void continuationAndGroups() {
    for (int d : new int[] {128, 256}) {
      Input input = synthetic(18, d, true);
      Result reference = run("java", input);
      for (int cut : new int[] {1, 7, 8})
        for (String name : arms()) {
          Result prefix = run(name, slice(input, 0, cut, input.initial));
          Result suffix = run(name, slice(input, cut, 18 - cut, prefix.state));
          float[] out = new float[reference.output.length];
          System.arraycopy(prefix.output, 0, out, 0, prefix.output.length);
          System.arraycopy(suffix.output, 0, out, prefix.output.length, suffix.output.length);
          System.out.println(
              "continuation\t"
                  + d
                  + "\t"
                  + cut
                  + "\t"
                  + name
                  + "\t"
                  + diff(reference.output, out)
                  + "\t"
                  + diff(reference.state, suffix.state));
          if (requiresExact(name)
              && (!Arrays.equals(reference.output, out)
                  || !Arrays.equals(reference.state, suffix.state)))
            throw new AssertionError("continuation changed bytes " + name);
        }
      int[] slots = {3, 0, 4};
      Input rows = synthetic(slots.length, d, true);
      int size = rows.vh * d * d;
      float[] initial = random(new SplittableRandom(17), 5 * size, .01f);
      float[] javaState = initial.clone(), javaOut = new float[rows.v.length];
      for (int row = 0; row < slots.length; row++) {
        Result r =
            run(
                "java",
                slice(
                    rows,
                    row,
                    1,
                    Arrays.copyOfRange(initial, slots[row] * size, (slots[row] + 1) * size)));
        System.arraycopy(r.state, 0, javaState, slots[row] * size, size);
        System.arraycopy(r.output, 0, javaOut, row * rows.vh * d, rows.vh * d);
      }
      for (var entry : libraries.entrySet()) {
        float[] state = initial.clone(), out = new float[rows.v.length];
        entry
            .getValue()
            .groupedGatedDeltaNetF32(
                rows.q,
                rows.k,
                rows.v,
                rows.g,
                rows.b,
                state,
                out,
                slots,
                slots.length,
                5,
                rows.kh,
                rows.vh,
                d,
                d);
        for (int slot : new int[] {1, 2})
          if (!Arrays.equals(
              Arrays.copyOfRange(initial, slot * size, (slot + 1) * size),
              Arrays.copyOfRange(state, slot * size, (slot + 1) * size)))
            throw new AssertionError("inactive slot changed");
        System.out.println(
            "grouped\t"
                + d
                + "\t"
                + entry.getKey()
                + "\t"
                + diff(javaOut, out)
                + "\t"
                + diff(javaState, state));
      }
    }
  }

  static void real(Path model, Path nativePath, String text, int count) throws Exception {
    try (var delegate = RustGgufBatchedMatrixKernel.open(nativePath)) {
      String[] selected = {"java"};
      boolean[] capture = {true};
      GgufBatchedMatrixKernel wrapper =
          (GgufBatchedMatrixKernel)
              Proxy.newProxyInstance(
                  GdnProof.class.getClassLoader(),
                  new Class<?>[] {GgufBatchedMatrixKernel.class},
                  (proxy, method, a) -> {
                    if (method.getName().equals("supportsGatedDeltaNet")) return true;
                    if (method.getName().equals("supportsGroupedGatedDeltaNet")) return false;
                    if (method.getName().equals("gatedDeltaNet")) {
                      Input input = Input.capture(a);
                      if (capture[0]) compare("real-call-" + calls, input);
                      apply(selected[0], input, (float[]) a[5], (float[]) a[6]);
                      calls++;
                      return null;
                    }
                    // The enclosing backend owns the wrapper; delegate is closed by this try block.
                    if (method.getName().equals("close")) return null;
                    try {
                      return method.invoke(delegate, a);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  });
      try (var backend = PureJavaBackend.load(model, wrapper)) {
        int[] encoded = backend.tokenizer().encode(text.repeat(Math.max(1, count)));
        if (count == 0) count = encoded.length;
        if (encoded.length < count) throw new AssertionError("not enough input tokens");
        int[] tokens = Arrays.copyOf(encoded, count);
        float[] baseline = null;
        System.out.println("tokens\t" + Arrays.toString(tokens));
        for (String arm : arms()) {
          selected[0] = arm;
          capture[0] = arm.equals("java");
          calls = 0;
          backend.reset();
          try (var session = backend.openSession()) {
            if (count > 1) backend.prefill(session, Arrays.copyOf(tokens, count - 1), 0);
            float[] logits = backend.forward(session, tokens[count - 1], count - 1).clone();
            if (baseline == null) baseline = logits;
            System.out.println(
                "logits\t"
                    + arm
                    + "\t"
                    + count
                    + "\t"
                    + calls
                    + "\t"
                    + diff(baseline, logits)
                    + "\t"
                    + argmax(logits));
            if (calls == 0) throw new AssertionError("recurrence did not execute");
          }
        }
      }
    }
  }

  static int argmax(float[] x) {
    int best = 0;
    for (int n = 1; n < x.length; n++) if (x[n] > x[best]) best = n;
    return best;
  }

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.ROOT);
    libraries.put("native-auto", NativeKernelLibrary.open(Path.of(args[0])));
    libraries.put("native-sequential", NativeKernelLibrary.open(Path.of(args[1])));
    libraries.put("native-aligned", NativeKernelLibrary.open(Path.of(args[2])));
    try {
      if (args.length > 3)
        real(
            Path.of(args[3]),
            Path.of(args[0]),
            args[4].startsWith("@")
                ? java.nio.file.Files.readString(Path.of(args[4].substring(1)))
                : args[4],
            Integer.parseInt(args[5]));
      else {
        for (int d : new int[] {128, 256})
          for (int t : new int[] {1, 7, 8, 18, 64, 128, 512})
            for (boolean nonzero : new boolean[] {false, true})
              compare("synthetic-" + nonzero, synthetic(t, d, nonzero));
        continuationAndGroups();
      }
    } finally {
      for (var lib : libraries.values()) lib.close();
    }
  }
}

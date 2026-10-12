package com.integrallis.models.backend.purejava.qwen35;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Standalone campaign runner. Output is line-oriented JSON, flushed per observation. */
public final class GdnCampaign {
  static final ObjectMapper JSON = new ObjectMapper();
  static String arm = "java";
  static long calls, recurrenceNanos;
  static final List<GdnProof.Input> tape = new ArrayList<>();
  static boolean tracing;
  static boolean firstInputDifference;

  static void emit(Map<String, ?> value) throws Exception {
    System.out.println(JSON.writeValueAsString(value));
  }

  static GgufBatchedMatrixKernel wrap(GgufBatchedMatrixKernel delegate) {
    return (GgufBatchedMatrixKernel)
        Proxy.newProxyInstance(
            GdnCampaign.class.getClassLoader(),
            new Class<?>[] {GgufBatchedMatrixKernel.class},
            (proxy, method, a) -> {
              if (method.getName().equals("supportsGatedDeltaNet")) return true;
              if (method.getName().equals("supportsGroupedGatedDeltaNet")) return false;
              if (method.getName().equals("gatedDeltaNet")) {
                var input =
                    new GdnProof.Input(
                        (float[]) a[0],
                        (float[]) a[1],
                        (float[]) a[2],
                        (float[]) a[3],
                        (float[]) a[4],
                        (float[]) a[5],
                        (int) a[7],
                        (int) a[8],
                        (int) a[9],
                        (int) a[10],
                        (int) a[11]);
                GdnProof.Result shadow = null;
                if (tracing) {
                  if (arm.equals("java")) tape.add(GdnProof.Input.capture(a));
                  else {
                    var before = tape.get((int) calls);
                    String[] names = {"query", "key", "value", "decay", "beta", "initialState"};
                    float[][] expected = {
                      before.q(), before.k(), before.v(), before.g(), before.b(), before.initial()
                    };
                    float[][] actual = {
                      input.q(), input.k(), input.v(), input.g(), input.b(), input.initial()
                    };
                    int[] lengths = {
                      input.tokens() * input.kh() * input.kd(),
                          input.tokens() * input.kh() * input.kd(),
                      input.tokens() * input.vh() * input.vd(), input.tokens() * input.vh(),
                      input.tokens() * input.vh(), input.vh() * input.kd() * input.vd()
                    };
                    for (int n = 0; n < names.length; n++)
                      if (!Arrays.equals(expected[n], 0, lengths[n], actual[n], 0, lengths[n])
                          && !firstInputDifference) {
                        emit(
                            Map.of(
                                "kind",
                                "first-input-difference",
                                "arm",
                                arm,
                                "call",
                                calls,
                                "field",
                                names[n],
                                "difference",
                                GdnProof.diff(
                                    Arrays.copyOf(expected[n], lengths[n]),
                                    Arrays.copyOf(actual[n], lengths[n]))));
                        firstInputDifference = true;
                      }
                  }
                  if (!arm.equals("java")) shadow = GdnProof.run("java", input);
                }
                long start = System.nanoTime();
                if (arm.equals("native-shared")) {
                  try {
                    method.invoke(delegate, a);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                } else {
                  GdnProof.apply(arm, input, (float[]) a[5], (float[]) a[6]);
                }
                recurrenceNanos += System.nanoTime() - start;
                if (shadow != null) {
                  float[] actual = Arrays.copyOf((float[]) a[6], shadow.output().length);
                  if (!Arrays.equals(shadow.output(), actual)
                      || !Arrays.equals(shadow.state(), (float[]) a[5])) {
                    emit(
                        Map.of(
                            "kind",
                            "same-input-recurrence-difference",
                            "arm",
                            arm,
                            "call",
                            calls,
                            "output",
                            GdnProof.diff(shadow.output(), actual),
                            "state",
                            GdnProof.diff(shadow.state(), (float[]) a[5])));
                    throw new AssertionError("same-input recurrence differs");
                  }
                }
                calls++;
                return null;
              }
              if (method.getName().equals("close")) return null;
              try {
                return method.invoke(delegate, a);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  static void micro() throws Exception {
    for (int d : new int[] {128, 256})
      for (int tokens : new int[] {1, 7, 8, 18, 64, 128, 512}) {
        var i = GdnProof.synthetic(tokens, d, true);
        var expected = GdnProof.run("java", i);
        // Each arm gets its own caller-owned state/output. Reset outside the timed region.
        for (int rep = -5; rep < 9; rep++) {
          List<String> order = new ArrayList<>(GdnProof.arms());
          Collections.rotate(order, Math.floorMod(rep, order.size()));
          if ((rep & 1) != 0) Collections.reverse(order);
          for (String name : order) {
            float[] state = i.initial().clone(), output = new float[tokens * i.vh() * i.vd()];
            long start = System.nanoTime();
            GdnProof.apply(name, i, state, output);
            long elapsed = System.nanoTime() - start;
            if (rep >= 0)
              emit(
                  Map.of(
                      "kind",
                      "kernel",
                      "arm",
                      name,
                      "repeat",
                      rep,
                      "tokens",
                      tokens,
                      "dimension",
                      d,
                      "heads",
                      i.vh(),
                      "nanos",
                      elapsed,
                      "outputDifference",
                      GdnProof.diff(expected.output(), output),
                      "stateDifference",
                      GdnProof.diff(expected.state(), state)));
          }
        }
      }
  }

  static void cohort(Path model, Path nativePath, Path tasksPath) throws Exception {
    JsonNode tasks = JSON.readTree(tasksPath.toFile());
    try (var delegate = RustGgufBatchedMatrixKernel.open(nativePath);
        var backend = PureJavaBackend.load(model, wrap(delegate))) {
      if (Boolean.getBoolean("gdn.sharedNativeOnly")) {
        if (!GdnProof.libraries.isEmpty()) throw new AssertionError("extra native contexts");
        System.err.println(
            "GDN_SHARED_CONTEXT providers=1 separateRecurrenceContexts=0 library=" + nativePath);
      }
      Map<String, float[]> baselines = new HashMap<>();
      // Warm all routes with the first public item; preserve these observations as warmups.
      for (int round = 0; round < 3; round++)
        for (String name : GdnProof.arms())
          decision(backend, tasks.get(0), name, -round - 1, baselines, false);
      for (int n = 0; n < tasks.size(); n++) {
        JsonNode task = tasks.get(n);
        // Baseline first for correctness; all timing claims use the counterbalanced phase below.
        for (String name : GdnProof.arms()) decision(backend, task, name, 0, baselines, true);
      }
      if (Boolean.getBoolean("gdn.correctnessOnly")) return;
      List<JsonNode> timing = new ArrayList<>();
      for (int n = 0; n < tasks.size(); n++) {
        JsonNode t = tasks.get(n);
        String id = t.path("id").asText();
        if (Boolean.getBoolean("gdn.timeAllCases")
            || n < 6
            || (n >= 114 && n < 120)
            || (id.startsWith("snips-") && (id.endsWith("-0") || id.endsWith("-1")))) timing.add(t);
      }
      for (int rep = 1; rep <= Integer.getInteger("gdn.timingRepeats", 5); rep++)
        for (int n = 0; n < timing.size(); n++) {
          List<String> order = new ArrayList<>(GdnProof.arms());
          Collections.rotate(order, (n + rep) % order.size());
          if ((rep & 1) != 0) Collections.reverse(order);
          for (String name : order) decision(backend, timing.get(n), name, rep, baselines, true);
        }
    }
  }

  static void decision(
      PureJavaBackend backend,
      JsonNode task,
      String name,
      int repeat,
      Map<String, float[]> baselines,
      boolean check)
      throws Exception {
    arm = name;
    calls = 0;
    recurrenceNanos = 0;
    backend.reset();
    String id = task.path("id").asText();
    tracing =
        repeat == 0
            && id.equals(System.getProperty("gdn.traceCase"))
            && Set.of("java", "java-head").contains(name);
    firstInputDifference = false;
    if (tracing && name.equals("java")) tape.clear();
    int[] tokens = backend.tokenizer().encode(task.path("prompt").asText());
    float[] logits;
    long start = System.nanoTime();
    try (var session = backend.openSession()) {
      logits = backend.prefill(session, tokens, 0).clone();
    }
    long elapsed = System.nanoTime() - start;
    if (calls == 0) throw new AssertionError("silent route: " + name);
    if (name.equals("java") && repeat == 0) baselines.put(id, logits);
    float[] baseline = baselines.getOrDefault(id, logits);
    String difference = GdnProof.diff(baseline, logits);
    boolean exact = Arrays.equals(baseline, logits);
    var labels = task.path("labels");
    Map<String, Float> scores = new LinkedHashMap<>();
    int best = 0;
    float bestScore = -Float.MAX_VALUE;
    for (int j = 0; j < labels.size(); j++) {
      int[] letter = backend.tokenizer().encode(" " + (char) ('A' + j));
      float score = logits[letter[letter.length - 1]];
      scores.put(labels.get(j).asText(), score);
      if (score > bestScore) {
        best = j;
        bestScore = score;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", "decision");
    result.put("dataset", task.path("dataset").asText());
    result.put("id", id);
    result.put("arm", name);
    result.put("repeat", repeat);
    result.put("tokens", tokens.length);
    result.put("calls", calls);
    result.put("nanos", elapsed);
    result.put("recurrenceNanos", recurrenceNanos);
    result.put("difference", difference);
    result.put("prediction", labels.get(best).asText());
    result.put("expected", task.path("expected").asText());
    result.put("letterLogits", scores);
    result.put("argmax", GdnProof.argmax(logits));
    emit(result);
    if (check && GdnProof.requiresExact(name) && !exact) {
      tracing = false;
      arm = "java";
      backend.reset();
      try (var session = backend.openSession()) {
        float[] replay = backend.prefill(session, tokens, 0).clone();
        emit(
            Map.of(
                "kind",
                "reference-replay",
                "id",
                id,
                "againstOriginal",
                GdnProof.diff(baseline, replay),
                "againstCandidate",
                GdnProof.diff(logits, replay)));
      }
      throw new AssertionError(
          "exact candidate changed logits: " + id + " " + name + " " + difference);
    }
  }

  public static void main(String[] args) throws Exception {
    if (!Boolean.getBoolean("gdn.sharedNativeOnly")) {
      GdnProof.libraries.put("native-auto", NativeKernelLibrary.open(Path.of(args[0])));
      GdnProof.libraries.put("native-sequential", NativeKernelLibrary.open(Path.of(args[1])));
      GdnProof.libraries.put("native-aligned", NativeKernelLibrary.open(Path.of(args[2])));
      String parallelLibrary = System.getProperty("gdn.nativeParallelLibrary");
      if (parallelLibrary != null)
        GdnProof.libraries.put(
            "native-aligned-parallel", NativeKernelLibrary.open(Path.of(parallelLibrary)));
    }
    try {
      if (args[3].equals("micro")) micro();
      else cohort(Path.of(args[4]), Path.of(args[0]), Path.of(args[5]));
    } finally {
      for (var lib : GdnProof.libraries.values()) lib.close();
    }
  }
}

package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Repeated unchanged Java reference, recording the first changed recurrence input. */
public final class GdnStabilityCheck {
  static final List<float[][]> tape = new ArrayList<>();
  static int observation, call;
  static boolean captured;
  static Path out;

  static float[][] fields(Object[] a) {
    int t = (int) a[7], kh = (int) a[8], vh = (int) a[9];
    int kd = (int) a[10], vd = (int) a[11];
    int[] lengths = {t * kh * kd, t * kh * kd, t * vh * vd, t * vh, t * vh, vh * kd * vd};
    float[][] result = new float[6][];
    for (int n = 0; n < result.length; n++) result[n] = Arrays.copyOf((float[]) a[n], lengths[n]);
    return result;
  }

  static void write(String name, float[][] values) throws Exception {
    try (var stream = new DataOutputStream(Files.newOutputStream(out.resolve(name)))) {
      stream.writeInt(values.length);
      for (float[] row : values) {
        stream.writeInt(row.length);
        for (float value : row) stream.writeInt(Float.floatToRawIntBits(value));
      }
    }
  }

  static GgufBatchedMatrixKernel capture(GgufBatchedMatrixKernel delegate) {
    return (GgufBatchedMatrixKernel)
        Proxy.newProxyInstance(
            GdnStabilityCheck.class.getClassLoader(),
            new Class<?>[] {GgufBatchedMatrixKernel.class},
            (proxy, method, a) -> {
              if (method.getName().equals("gatedDeltaNet")) {
                float[][] current = fields(a);
                if (observation == 0) tape.add(current);
                else if (!captured) {
                  float[][] first = tape.get(call);
                  String[] names = {"query", "key", "value", "decay", "beta", "initialState"};
                  for (int n = 0; n < names.length; n++) {
                    if (!Arrays.equals(first[n], current[n])) {
                      GdnCampaign.emit(
                          Map.of(
                              "kind",
                              "first-input-change",
                              "observation",
                              observation,
                              "call",
                              call,
                              "field",
                              names[n],
                              "difference",
                              GdnProof.diff(first[n], current[n])));
                      write("first-input.bin", first);
                      write("changed-input.bin", current);
                      captured = true;
                      break;
                    }
                  }
                }
                call++;
              }
              try {
                return method.invoke(delegate, a);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  public static void main(String[] args) throws Exception {
    out = Path.of(args[3]);
    Files.createDirectories(out);
    var tasks = GdnCampaign.JSON.readTree(Path.of(args[2]).toFile());
    var task = tasks.findValues("id");
    if (task.isEmpty()) throw new IllegalArgumentException("missing tasks");
    String id = System.getProperty("gdn.stabilityCase", "easy-extraction-06");
    String prompt = null;
    for (var row : tasks)
      if (row.path("id").asText().equals(id)) prompt = row.path("prompt").asText();
    if (prompt == null) throw new IllegalArgumentException(id);
    try (var nativeKernel = RustGgufBatchedMatrixKernel.open(Path.of(args[0]));
        var backend =
            PureJavaBackend.load(Path.of(args[1]), capture(GdnCampaign.wrap(nativeKernel)))) {
      int[] tokens = backend.tokenizer().encode(prompt);
      float[] first = null, previous = null;
      for (observation = 0;
          observation < Integer.getInteger("gdn.stabilityRepeats", 24);
          observation++) {
        call = 0;
        GdnCampaign.calls = 0;
        GdnCampaign.recurrenceNanos = 0;
        GdnCampaign.arm = "java";
        backend.reset();
        float[] logits;
        long start = System.nanoTime();
        try (var session = backend.openSession()) {
          logits = backend.prefill(session, tokens, 0).clone();
        }
        long nanos = System.nanoTime() - start;
        if (first == null) {
          first = logits;
          previous = logits;
        }
        GdnCampaign.emit(
            Map.of(
                "kind",
                "reference-stability",
                "id",
                id,
                "observation",
                observation,
                "tokens",
                tokens.length,
                "calls",
                call,
                "nanos",
                nanos,
                "againstFirst",
                GdnProof.diff(first, logits),
                "againstPrevious",
                GdnProof.diff(previous, logits)));
        if (!Arrays.equals(first, logits) && !Files.exists(out.resolve("changed-logits.bin"))) {
          write("first-logits.bin", new float[][] {first});
          write("changed-logits.bin", new float[][] {logits});
        }
        previous = logits;
      }
    }
  }
}

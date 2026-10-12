package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.gguf.GgufTensorType;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

/** Diagnostic only: compare identical projection rows across prefix and decode dispatches. */
public final class GroupedRouteProbe {
  record Projection(String hash, int batch, String method) {}
  static final Map<String, Projection> reference = new HashMap<>();
  static String phase;
  static long matched, mismatches;
  static void record(float[] output, float[] input, MemorySegment weights, GgufTensorType type,
      int batch, int rows, int cols, String method) throws Exception {
    for (int row = 0; row < batch; row++) {
      String inputHash = ProductionRouteProof.digest(Arrays.copyOfRange(input, row * cols, (row + 1) * cols));
      String key = weights.address() + ":" + type + ":" + rows + ":" + cols + ":" + inputHash;
      String outputHash = ProductionRouteProof.digest(Arrays.copyOfRange(output, row * rows, (row + 1) * rows));
      Projection prior = reference.get(key);
      if (prior == null) {
        if (phase.equals("full")) reference.put(key, new Projection(outputHash, batch, method));
      } else {
        matched++;
        if (!prior.hash().equals(outputHash)) {
          mismatches++;
          if (mismatches <= 12) ProductionRouteProof.emit(Map.of("kind", "projection-mismatch",
              "phase", phase, "format", type.toString(), "rows", rows, "cols", cols,
              "batch", batch, "referenceBatch", prior.batch(), "method", method,
              "referenceMethod", prior.method(), "inputSha256", inputHash));
        }
      }
    }
  }
  static GgufBatchedMatrixKernel observe(GgufBatchedMatrixKernel provider) {
    return (GgufBatchedMatrixKernel) Proxy.newProxyInstance(GroupedRouteProbe.class.getClassLoader(),
        new Class<?>[] {GgufBatchedMatrixKernel.class}, (proxy, method, a) -> {
          Object result;
          try { result = method.invoke(provider, a); }
          catch (InvocationTargetException e) { throw e.getCause(); }
          String name = method.getName();
          switch (name) {
            case "multiply" -> record((float[])a[0], (float[])a[1], (MemorySegment)a[2], (GgufTensorType)a[3], (int)a[4], (int)a[5], (int)a[6], name);
            case "multiplyDual", "multiplyTriple" -> {
              int count = name.equals("multiplyDual") ? 2 : 3;
              int at = count * 4;
              for (int n = 0; n < count; n++) record((float[])a[4*n], (float[])a[at],
                  (MemorySegment)a[4*n+1], (GgufTensorType)a[4*n+2], (int)a[at+1], (int)a[4*n+3], (int)a[at+2], name);
            }
            case "multiplyGrouped", "multiplyIndependent" -> {
              for (int n = 0; n < (int)a[4]; n++) record(((float[][])a[0])[n],
                  name.equals("multiplyGrouped") ? (float[])a[5] : ((float[][])a[5])[n],
                  ((MemorySegment[])a[1])[n], ((GgufTensorType[])a[2])[n], (int)a[6], ((int[])a[3])[n], (int)a[7], name);
            }
            case "multiplyRaggedIndependent" -> {
              for (int n = 0; n < (int)a[5]; n++) record(((float[][])a[0])[n], ((float[][])a[6])[n],
                  ((MemorySegment[])a[1])[n], ((GgufTensorType[])a[2])[n], ((int[])a[4])[n], ((int[])a[3])[n], (int)a[7], name);
            }
          }
          return result;
        });
  }
  public static void main(String[] args) throws Exception {
    var tasks = ProductionRouteProof.JSON.readTree(Path.of(args[2]).toFile());
    try (var provider = RustGgufBatchedMatrixKernel.open(Path.of(args[1]));
         var backend = PureJavaBackend.load(Path.of(args[0]), ProductionRouteProof.WORKLOAD, observe(provider))) {
      int[] tokens = backend.tokenizer().encode(tasks.get(0).path("prompt").asText());
      float[] baseline = null;
      for (String variant : new String[] {"full", "repeat", "split", "group"}) {
        phase = variant; matched = 0; mismatches = 0;
        backend.reset();
        float[] logits;
        if (variant.equals("full") || variant.equals("repeat")) logits = backend.prefill(tokens, 0);
        else {
          backend.prefill(Arrays.copyOf(tokens, tokens.length - 1), 0);
          logits = variant.equals("split") ? backend.forward(tokens[tokens.length-1], tokens.length-1)
              : backend.decideGrouped(new int[][] {{tokens[tokens.length-1]}})[0];
        }
        if (baseline == null) baseline = logits.clone();
        ProductionRouteProof.emit(Map.of("kind", "phase", "phase", phase, "matchedProjections", matched,
            "projectionMismatches", mismatches, "difference", GdnProof.diff(baseline, logits),
            "argmax", ProductionRouteProof.argmax(logits)));
      }
    }
  }
}

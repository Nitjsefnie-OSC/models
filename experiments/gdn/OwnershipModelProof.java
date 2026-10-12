package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

/** Real-weight attention patch and recurrence parity through greedy session continuation. */
public final class OwnershipModelProof {
  static long attentionCalls;

  static GgufBatchedMatrixKernel countAttention(GgufBatchedMatrixKernel provider) {
    return (GgufBatchedMatrixKernel)
        Proxy.newProxyInstance(
            OwnershipModelProof.class.getClassLoader(),
            new Class<?>[] {GgufBatchedMatrixKernel.class},
            (proxy, method, args) -> {
              if (method.getName().equals("groupedAttention")) attentionCalls++;
              try {
                return method.invoke(provider, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  public static void main(String[] args) throws Exception {
    boolean attention = Boolean.getBoolean("gdn.modelAttention");
    var tasks = GdnCampaign.JSON.readTree(Path.of(args[3]).toFile());
    var baseline = new HashMap<String, List<float[]>>();
    String[] arms =
        attention
            ? new String[] {"java", "java"}
            : new String[] {"java", "java", "java-tiled-stage", "native-shared"};
    for (int route = 0; route < arms.length; route++) {
      String library = args[route == 0 ? 0 : 1];
      try (var provider = RustGgufBatchedMatrixKernel.open(Path.of(library));
          var backend =
              PureJavaBackend.load(
                  Path.of(args[2]),
                  attention ? countAttention(provider) : GdnCampaign.wrap(provider))) {
        for (var task : tasks) {
          String id = task.path("id").asText();
          if (!id.equals("original-routing-06-0") && !id.equals("snips-AddToPlaylist-0")) continue;
          int[] tokens = backend.tokenizer().encode(task.path("prompt").asText());
          var observations = new ArrayList<float[]>();
          var selected = new ArrayList<Integer>();
          backend.reset();
          GdnCampaign.arm = arms[route];
          GdnCampaign.calls = 0;
          attentionCalls = 0;
          try (var session = backend.openSession()) {
            float[] logits = backend.prefill(session, tokens, 0).clone();
            observations.add(logits);
            for (int step = 0; step < 8; step++) {
              int next = GdnProof.argmax(logits);
              selected.add(next);
              logits = backend.forward(session, next, tokens.length + step).clone();
              observations.add(logits);
            }
          }
          if (route == 0) baseline.put(id, observations);
          else {
            var expected = baseline.get(id);
            if (expected == null) throw new AssertionError("missing baseline");
            for (int step = 0; step < expected.size(); step++)
              if (!Arrays.equals(expected.get(step), observations.get(step)))
                throw new AssertionError(
                    "route="
                        + route
                        + " id="
                        + id
                        + " step="
                        + step
                        + " "
                        + GdnProof.diff(expected.get(step), observations.get(step)));
          }
          if (attention ? attentionCalls == 0 : GdnCampaign.calls == 0)
            throw new AssertionError("intended operation did not execute");
          GdnCampaign.emit(
              Map.of(
                  "route",
                  route,
                  "arm",
                  arms[route],
                  "id",
                  id,
                  "tokens",
                  tokens.length,
                  "fullLogitObservations",
                  observations.size(),
                  "greedyTokens",
                  selected,
                  "recurrenceCalls",
                  GdnCampaign.calls,
                  "attentionCalls",
                  attentionCalls,
                  "exactAgainstOriginal",
                  true));
        }
      }
    }
    if (baseline.size() != 2) throw new AssertionError("both public datasets required");
    System.out.println(
        "PASS two public prompts, prefill and eight-token continuation, "
            + arms.length
            + " routes; attentionProof="
            + attention);
  }
}

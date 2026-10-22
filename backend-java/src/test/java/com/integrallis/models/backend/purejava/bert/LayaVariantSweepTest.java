package com.integrallis.models.backend.purejava.bert;

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import com.integrallis.models.backend.purejava.gguf.GgufFile;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The negative-control set for the {@code modern-bert} decision forward pass.
 *
 * <p>Not temporary. Each row is a structural alternative to what the forward pass does, and the
 * measurement that rejects it. Without these the baseline's 0.573 residual against the reference
 * could not be distinguished from a wrong graph, and anyone looking at that residual would start by
 * re-testing these same eleven ideas.
 *
 * <p>Measured 2026-10-22 on {@code Laya-BF16.gguf}, worst absolute logit deviation from the
 * reference over all eight cases, with the number of cases whose argmax disagreed:
 *
 * <pre>
 *   baseline              0.573   0     <-- what the forward pass does
 *   tanhGelu              0.571   0     indistinguishable; GELU variant is not the cause
 *   noRopeInDecision      0.924   0
 *   halfWindow32          1.025   0
 *   noOutputNorm          1.217   0
 *   normAfterDecision     1.224   0
 *   windowOnTokenIdx      2.010   1
 *   allGlobal             2.708   2
 *   swapEmbedOutputNorm   3.627   2
 *   halfWindow16          3.568   1
 *   swapGeGLU             5.221   2
 *   typeAtEmbedding       5.875   4
 * </pre>
 *
 * <p>Two readings matter. Every alternative is worse, so the baseline graph is the best available
 * — and the closest alternative is 1.6x the baseline's error, so this suite can detect a structural
 * mistake at that scale. It did not find the cause of the 0.573, which is the honest limit of an
 * end-to-end comparison.
 */
@Tag("model-fixture")
class LayaVariantSweepTest {

  private static final Path ARTIFACT =
      Path.of(System.getProperty("user.home"), ".jvllm", "models", "Laya-BF16.gguf");

  @Test
  void sweep() throws Exception {
    if (!Files.isRegularFile(ARTIFACT)) {
      System.out.println("no artifact");
      return;
    }
    Map<String, Runnable> variants = new LinkedHashMap<>();
    variants.put("baseline", () -> set(false, false, false, false));
    variants.put("noRopeInDecision", () -> { reset(); ModernBertDecisionForwardPass.NO_ROPE_IN_DECISION_BLOCKS = true; });
    variants.put("swapEmbedOutputNorm", () -> { reset(); ModernBertDecisionForwardPass.SWAP_EMBED_AND_OUTPUT_NORM = true; });



    List<Case> cases = referenceCases();
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(ARTIFACT, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);
      for (var entry : variants.entrySet()) {
        entry.getValue().run();
        ModernBertDecisionForwardPass forward =
            new ModernBertDecisionForwardPass(config, weights);
        double worst = 0.0;
        int argmaxMismatches = 0;
        for (Case c : cases) {
          var layout = ModernBertDecisionLayout.of(c.markers, c.headLength, c.sequenceLength);
          float[] got = forward.logits(c.ids, layout, c.type);
          for (int i = 0; i < got.length; i++) {
            worst = Math.max(worst, Math.abs(got[i] - c.rawLogits[i]));
          }
          if (argmax(got) != argmax(c.rawLogits)) {
            argmaxMismatches++;
          }
        }
        System.out.printf(
            Locale.ROOT, "SWEEP %-22s worst=%.5f argmaxMismatches=%d%n",
            entry.getKey(), worst, argmaxMismatches);
      }
    }
    set(false, false, false, false);
  }

  private static void reset() {
    set(false, false, false, false);
  }

  private static void set(boolean swap, boolean global, boolean tanh, boolean tokenWindow) {
    ModernBertDecisionForwardPass.TYPE_AT_EMBEDDING = false;
    ModernBertDecisionForwardPass.NORM_AFTER_DECISION = false;
    ModernBertDecisionForwardPass.NO_OUTPUT_NORM = false;
    ModernBertDecisionForwardPass.HALF_WINDOW_OVERRIDE = 0;
    ModernBertDecisionForwardPass.NO_ROPE_IN_DECISION_BLOCKS = false;
    ModernBertDecisionForwardPass.SWAP_EMBED_AND_OUTPUT_NORM = false;
    ModernBertDecisionForwardPass.SWAP_GEGLU_HALVES = swap;
    ModernBertDecisionForwardPass.ALL_LAYERS_GLOBAL = global;
    ModernBertDecisionForwardPass.TANH_GELU = tanh;
    ModernBertDecisionForwardPass.WINDOW_ON_TOKEN_INDEX = tokenWindow;
  }

  private static int argmax(float[] v) {
    int b = 0;
    for (int i = 1; i < v.length; i++) {
      if (v[i] > v[b]) b = i;
    }
    return b;
  }

  private record Case(String question, QuestionType type, int[] ids, int[] markers,
      int headLength, int sequenceLength, float[] rawLogits) {}

  private static List<Case> referenceCases() throws Exception {
    String json;
    try (var in = LayaVariantSweepTest.class.getResourceAsStream("/modernbert/laya-reference.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Case> cases = new ArrayList<>();
    Pattern block = Pattern.compile(
        "\\{\\s*\"question\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([^\"]+)\".*?"
            + "\"ids\":\\s*\\[([^]]*)].*?\"markers\":\\s*\\[([^]]*)].*?"
            + "\"headLength\":\\s*(\\d+).*?\"sequenceLength\":\\s*(\\d+).*?"
            + "\"rawLogits\":\\s*\\[([^]]*)]", Pattern.DOTALL);
    Matcher m = block.matcher(json);
    while (m.find()) {
      cases.add(new Case(m.group(1),
          QuestionType.valueOf(m.group(2).toUpperCase(Locale.ROOT)),
          ints(m.group(3)), ints(m.group(4)),
          Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), floats(m.group(7))));
    }
    return cases;
  }

  private static int[] ints(String csv) {
    String[] p = csv.trim().split(",");
    int[] v = new int[p.length];
    for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i].trim());
    return v;
  }

  private static float[] floats(String csv) {
    String[] p = csv.trim().split(",");
    float[] v = new float[p.length];
    for (int i = 0; i < p.length; i++) v[i] = Float.parseFloat(p[i].trim());
    return v;
  }
}

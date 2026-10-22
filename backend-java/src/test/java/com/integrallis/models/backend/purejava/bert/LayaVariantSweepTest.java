/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * <p><b>Read the warning before the table.</b> When this suite first ran, the forward pass was
 * wrong in three places at once and every alternative below still measured worse than it -- because
 * two of the three errors partially cancelled. The suite reported "baseline is best" for a baseline
 * that was broken. A one-variable-at-a-time sweep over an end-to-end number cannot detect
 * interacting errors, and nothing here should be read as validating the graph. What validated it
 * was comparing per-stage activations against the reference ({@code oracle/intermediates.py}),
 * which localised the defect to the decision blocks in a single run.
 *
 * <p>What the suite IS good for: each row is a specific structural mistake, and each number is the
 * damage that mistake does once the rest is correct. Measured 2026-10-22 on {@code Laya-BF16.gguf},
 * worst absolute logit deviation from the reference over all eight cases, with the number of cases
 * whose argmax disagreed:
 *
 * <pre>
 *   baseline              0.033   0     <-- what the forward pass does
 *   isolateInDecision     0.809   0     option isolation wrongly kept in the head
 *   swapEmbedOutputNorm   3.697   2
 *   ropeInDecision        5.196   7     rotary wrongly applied in the head
 * </pre>
 *
 * <p>The closest mistake is 24x the baseline's error, so this detects a structural change easily --
 * it just cannot attribute one.
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
    variants.put(
        "isolateInDecision",
        () -> {
          reset();
          ModernBertDecisionForwardPass.ISOLATE_IN_DECISION_BLOCKS = true;
        });
    variants.put(
        "ropeInDecision",
        () -> {
          reset();
          ModernBertDecisionForwardPass.ROPE_IN_DECISION_BLOCKS = true;
        });
    variants.put(
        "swapEmbedOutputNorm",
        () -> {
          reset();
          ModernBertDecisionForwardPass.SWAP_EMBED_AND_OUTPUT_NORM = true;
        });

    List<Case> cases = referenceCases();
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(ARTIFACT, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);
      for (var entry : variants.entrySet()) {
        entry.getValue().run();
        ModernBertDecisionForwardPass forward = new ModernBertDecisionForwardPass(config, weights);
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
            Locale.ROOT,
            "SWEEP %-22s worst=%.5f argmaxMismatches=%d%n",
            entry.getKey(),
            worst,
            argmaxMismatches);
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
    ModernBertDecisionForwardPass.ROPE_IN_DECISION_BLOCKS = false;
    ModernBertDecisionForwardPass.ISOLATE_IN_DECISION_BLOCKS = false;
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

  private record Case(
      String question,
      QuestionType type,
      int[] ids,
      int[] markers,
      int headLength,
      int sequenceLength,
      float[] rawLogits) {}

  private static List<Case> referenceCases() throws Exception {
    String json;
    try (var in =
        LayaVariantSweepTest.class.getResourceAsStream("/modernbert/laya-reference.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Case> cases = new ArrayList<>();
    Pattern block =
        Pattern.compile(
            "\\{\\s*\"question\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([^\"]+)\".*?"
                + "\"ids\":\\s*\\[([^]]*)].*?\"markers\":\\s*\\[([^]]*)].*?"
                + "\"headLength\":\\s*(\\d+).*?\"sequenceLength\":\\s*(\\d+).*?"
                + "\"rawLogits\":\\s*\\[([^]]*)]",
            Pattern.DOTALL);
    Matcher m = block.matcher(json);
    while (m.find()) {
      cases.add(
          new Case(
              m.group(1),
              QuestionType.valueOf(m.group(2).toUpperCase(Locale.ROOT)),
              ints(m.group(3)),
              ints(m.group(4)),
              Integer.parseInt(m.group(5)),
              Integer.parseInt(m.group(6)),
              floats(m.group(7))));
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

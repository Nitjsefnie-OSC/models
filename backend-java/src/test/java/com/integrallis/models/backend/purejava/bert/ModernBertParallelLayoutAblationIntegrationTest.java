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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import com.integrallis.models.backend.purejava.gguf.GgufFile;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Gate 4b: the parallel layout's symmetry, measured through the whole model.
 *
 * <p>Gate 4a checked the layout's index arithmetic without loading a weight. That is necessary and
 * not sufficient: the ids can be right while the model still treats slot 3 differently from slot 1,
 * which is what the layout exists to prevent and what only a forward pass can show.
 *
 * <p>The test builds a question whose options are <b>byte-identical</b> — the same option tokens
 * repeated into every slot — so the correct answer is known without a reference: every slot must
 * receive the same logit. Anything else is positional leakage, and its size is the bias a caller
 * would silently inherit.
 *
 * <p>The sequential control is the point. A flat result proves nothing on its own, because an
 * implementation that ignored the layout entirely would also return five equal logits for five
 * equal options if it simply read the same position each time. So the same tokens are run again
 * with ordinary sequential positions and no option isolation, and that must spread. The reference's
 * own comment measures this at 3.59 for five identical options; this asserts the two regimes differ
 * by a wide margin rather than pinning a number produced on other hardware.
 */
@Tag("model-fixture")
class ModernBertParallelLayoutAblationIntegrationTest {

  private static final Path ARTIFACT =
      Path.of(
          System.getProperty(
              "models.fixtures.layaBf16",
              Path.of(System.getProperty("user.home"), ".jvllm", "models", "Laya-BF16.gguf")
                  .toString()));

  /**
   * Identical slots must agree to this much.
   *
   * <p>Not zero: each slot's logit is accumulated over a different stretch of the same arrays, so
   * fp32 addition order differs between them. It is far below any spread that would matter to a
   * caller, and three orders of magnitude below what the sequential control produces.
   */
  private static final double SYMMETRY_TOLERANCE = 0.02;

  @Test
  void givesIdenticalOptionsIdenticalLogitsAndSequentialLayoutDoesNot() throws IOException {
    assumeTrue(Files.isRegularFile(ARTIFACT), "Laya-BF16.gguf is not installed at " + ARTIFACT);

    // Five slots, the arity the reference measured its own sequential spread at.
    int slots = 5;
    // A minimal, self-describing head: [CLS] choice question: pick [SEP] then the option slots,
    // then [SEP] and a short state. The ids come from the reference fixture's own vocabulary use:
    // 50281 [CLS], 50282 [SEP], 50284 [MASK].
    int[] prefix = {50281, 25132, 1953, 27, 2619, 50282};
    int[] optionSpan = {50284, 2032, 27, 642};
    int[] tail = {50282, 3221, 27, 642, 13, 253, 2608, 310, 7520};

    int[] ids = new int[prefix.length + slots * optionSpan.length + tail.length];
    int[] markers = new int[slots];
    int cursor = 0;
    System.arraycopy(prefix, 0, ids, cursor, prefix.length);
    cursor += prefix.length;
    for (int slot = 0; slot < slots; slot++) {
      markers[slot] = cursor;
      System.arraycopy(optionSpan, 0, ids, cursor, optionSpan.length);
      cursor += optionSpan.length;
    }
    int headLength = cursor + 1;
    System.arraycopy(tail, 0, ids, cursor, tail.length);

    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(ARTIFACT, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);
      ModernBertDecisionForwardPass forward = new ModernBertDecisionForwardPass(config, weights);

      ModernBertDecisionLayout parallel =
          ModernBertDecisionLayout.of(markers, headLength, ids.length);
      float[] parallelLogits = forward.logits(ids, parallel, QuestionType.CHOICE);

      // The control: the same tokens, ordinary sequential positions, nothing isolated.
      int[] sequentialPositions = new int[ids.length];
      for (int index = 0; index < ids.length; index++) {
        sequentialPositions[index] = index;
      }
      ModernBertDecisionLayout sequential =
          new ModernBertDecisionLayout(sequentialPositions, new int[ids.length], markers);
      float[] sequentialLogits = forward.logits(ids, sequential, QuestionType.CHOICE);

      double parallelSpread = spread(parallelLogits);
      double sequentialSpread = spread(sequentialLogits);
      System.out.printf(
          "identical options x%d: parallel spread %.6f, sequential spread %.6f%n",
          slots, parallelSpread, sequentialSpread);
      System.out.println("  parallel   " + Arrays.toString(parallelLogits));
      System.out.println("  sequential " + Arrays.toString(sequentialLogits));

      assertThat(parallelSpread)
          .as("identical options must receive identical logits under the parallel layout")
          .isLessThan(SYMMETRY_TOLERANCE);
      assertThat(sequentialSpread)
          .as(
              "the control must spread, or this test would pass for an implementation that ignored"
                  + " the layout")
          .isGreaterThan(SYMMETRY_TOLERANCE * 10);
    }
  }

  private static double spread(float[] values) {
    float lowest = values[0];
    float highest = values[0];
    for (float value : values) {
      lowest = Math.min(lowest, value);
      highest = Math.max(highest, value);
    }
    return highest - lowest;
  }
}

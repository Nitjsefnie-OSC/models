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
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Gate 3+5b: the whole {@code modern-bert} decision forward pass against the reference's logits.
 *
 * <p>The eight cases' <em>raw</em> logits come from the upstream {@code laya} package at the
 * checkpoint revision {@code ggml-org/Laya-GGUF} was converted from. They are the right gate for
 * the encoder as well as the head: everything from the token embedding to the scorer is upstream of
 * them, so a wrong rope base, a swapped GeGLU half, a window measured on position ids, or the
 * llama.cpp reranker's norm order all move these numbers.
 *
 * <p><b>Tolerance, fixed before the comparison ran.</b> The reference computes in torch at
 * bf16/fp32 and this runs Q8_0 weights, so the two cannot agree to arithmetic precision and the
 * gate is not written as if they could:
 *
 * <ul>
 *   <li><b>argmax must match on all eight cases, with no tolerance.</b> A decision model that picks
 *       a different option is wrong whatever its logits look like, and this is the only assertion
 *       that corresponds to what a caller receives.
 *   <li><b>every logit within 0.15 absolute.</b> Logit magnitudes here run about 0.4 to 3.6, so
 *       0.15 admits Q8_0 round-off while refusing any structural error — the smallest real one
 *       available, swapping the two rotary bases, moves them by whole units.
 * </ul>
 *
 * <p><b>STATUS 2026-10-22: passes.</b> Worst deviation 0.06296 at Q8_0 and 0.03282 at BF16, against
 * the 0.15 declared above, and the chosen option matches on all eight cases. The tolerance was
 * never widened: it failed at 0.573 until three defects in the decision blocks were found and
 * fixed, and the history is in {@code experiments/laya-modern-bert/README.md} because the way it
 * was found matters more than the number.
 *
 * <p>Those three were ReLU rather than GELU in the decision MLP, rotary wrongly applied in the
 * head, and option isolation wrongly applied in the head -- each read out of laya's own source once
 * the reference's per-stage activations localised the divergence to {@code head.layers.0}. Eleven
 * structural hypotheses had already been swept from this end-to-end number alone and every one
 * looked worse than the baseline, because two of the three errors partially cancelled. One scalar
 * after thirty blocks cannot localise anything, which is the whole reason {@code
 * oracle/intermediates.py} exists.
 */
@Tag("model-fixture")
class ModernBertDecisionForwardPassIntegrationTest {

  private static final double LOGIT_TOLERANCE = 0.15;

  private static final Path MODELS =
      Path.of(
          System.getProperty(
              "models.fixtures.directory",
              Path.of(System.getProperty("user.home"), ".jvllm", "models").toString()));

  /** The quantization that ships, and the one that isolates quantization error from ours. */
  private static final Path ARTIFACT =
      Path.of(
          System.getProperty("models.fixtures.laya", MODELS.resolve("Laya-Q8_0.gguf").toString()));

  private static final Path BF16_ARTIFACT =
      Path.of(
          System.getProperty(
              "models.fixtures.layaBf16", MODELS.resolve("Laya-BF16.gguf").toString()));

  @Test
  void reproducesTheReferenceLogitsForEveryCase() throws IOException {
    assumeTrue(Files.isRegularFile(ARTIFACT), "Laya-Q8_0.gguf is not installed at " + ARTIFACT);
    List<Case> cases = referenceCases();
    assertThat(cases).hasSize(8);

    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(ARTIFACT, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);
      ModernBertDecisionForwardPass forward = new ModernBertDecisionForwardPass(config, weights);

      // Nothing in the file goes unread; a loader can satisfy every shape and still ignore a
      // tensor the forward pass needs, which is a wrong answer rather than a failure.
      assertThat(ModernBertWeights.unclaimedTensors(file)).isEmpty();

      List<String> deviations = new ArrayList<>();
      for (Case expected : cases) {
        ModernBertDecisionLayout layout =
            ModernBertDecisionLayout.of(
                expected.markers, expected.headLength, expected.sequenceLength);
        float[] actual = forward.logits(expected.ids, layout, expected.type);

        assertThat(actual)
            .as("option count for %s", expected.question)
            .hasSameSizeAs(expected.rawLogits);
        assertThat(argmax(actual))
            .as(
                "chosen option for %s: reference %s, ours %s",
                expected.question,
                java.util.Arrays.toString(expected.rawLogits),
                java.util.Arrays.toString(actual))
            .isEqualTo(argmax(expected.rawLogits));

        for (int option = 0; option < actual.length; option++) {
          double delta = Math.abs(actual[option] - expected.rawLogits[option]);
          if (delta > LOGIT_TOLERANCE) {
            deviations.add(
                String.format(
                    Locale.ROOT,
                    "%s[%d] reference %.5f ours %.5f delta %.5f",
                    expected.question,
                    option,
                    expected.rawLogits[option],
                    actual[option],
                    delta));
          }
        }
        System.out.printf(
            "%-16s %-6s opts=%2d maxDelta=%.5f%n",
            expected.question, expected.type, actual.length, maxDelta(actual, expected.rawLogits));
      }
      assertThat(deviations).as("logits outside %s absolute", LOGIT_TOLERANCE).isEmpty();
    }
  }

  @Test
  void reproducesTheReferenceLogitsMoreCloselyAtBf16ThanAtQ8() throws IOException {
    // The diagnostic the Q8_0 run's residual demands, rather than widening a tolerance until it
    // passes. The reference computes in torch at bf16/fp32; if our arithmetic is right, the BF16
    // artifact must agree with it more closely than the Q8_0 one does, and the difference between
    // the two IS the quantization error. If BF16 were no better, the residual would be ours.
    assumeTrue(Files.isRegularFile(ARTIFACT), "Laya-Q8_0.gguf is not installed");
    assumeTrue(Files.isRegularFile(BF16_ARTIFACT), "Laya-BF16.gguf is not installed");

    double q8Worst = worstDelta(ARTIFACT);
    double bf16Worst = worstDelta(BF16_ARTIFACT);
    System.out.printf("worst logit delta: Q8_0 %.5f, BF16 %.5f%n", q8Worst, bf16Worst);

    assertThat(bf16Worst)
        .as("BF16 must track the reference more closely than Q8_0 does")
        .isLessThan(q8Worst);
    assertThat(bf16Worst)
        .as("at BF16 nothing but our own arithmetic separates us from the reference")
        .isLessThan(LOGIT_TOLERANCE);
  }

  /** The worst absolute logit deviation over all eight cases, for one artifact. */
  private static double worstDelta(Path artifact) throws IOException {
    List<Case> cases = referenceCases();
    double worst = 0.0;
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(artifact, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);
      ModernBertDecisionForwardPass forward = new ModernBertDecisionForwardPass(config, weights);
      for (Case expected : cases) {
        ModernBertDecisionLayout layout =
            ModernBertDecisionLayout.of(
                expected.markers, expected.headLength, expected.sequenceLength);
        worst =
            Math.max(
                worst,
                maxDelta(forward.logits(expected.ids, layout, expected.type), expected.rawLogits));
      }
    }
    return worst;
  }

  @Test
  void swappingTheTwoRotaryBasesBreaksIt() throws IOException {
    // The negative control. Without it a passing comparison says only that some function agreed,
    // not that this gate can detect the error it was written for.
    assumeTrue(Files.isRegularFile(ARTIFACT), "Laya-Q8_0.gguf is not installed at " + ARTIFACT);
    Case first = referenceCases().get(0);

    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = GgufParser.parse(ARTIFACT, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertConfig swapped =
          new ModernBertConfig(
              config.architecture(),
              config.embeddingDim(),
              config.numLayers(),
              config.numHeads(),
              config.vocabSize(),
              config.contextLength(),
              config.feedForwardLengths(),
              config.layerNormEps(),
              config.ropeThetaSlidingWindow(),
              config.ropeTheta(),
              config.slidingWindow(),
              config.slidingWindowPattern(),
              config.pooling(),
              config.decision());
      ModernBertWeights weights = ModernBertWeights.load(file, config);

      ModernBertDecisionLayout layout =
          ModernBertDecisionLayout.of(first.markers, first.headLength, first.sequenceLength);
      float[] correct =
          new ModernBertDecisionForwardPass(config, weights).logits(first.ids, layout, first.type);
      float[] broken =
          new ModernBertDecisionForwardPass(swapped, weights).logits(first.ids, layout, first.type);

      // The property that matters is that THIS gate can catch the error, so the bar is the gate's
      // own tolerance. An earlier version demanded four times it, which was a guess about the
      // magnitude rather than a requirement: measured, the swap moves these logits by 0.43, which
      // the gate rejects and Q8_0 round-off never reaches.
      assertThat(maxDelta(correct, broken))
          .as("swapping 160000 and 10000 must move the logits past the gate's tolerance")
          .isGreaterThan(LOGIT_TOLERANCE);
    }
  }

  private static double maxDelta(float[] left, float[] right) {
    double worst = 0.0;
    for (int index = 0; index < left.length; index++) {
      worst = Math.max(worst, Math.abs(left[index] - right[index]));
    }
    return worst;
  }

  private static int argmax(float[] values) {
    int best = 0;
    for (int index = 1; index < values.length; index++) {
      if (values[index] > values[best]) {
        best = index;
      }
    }
    return best;
  }

  private record Case(
      String question,
      QuestionType type,
      int[] ids,
      int[] markers,
      int headLength,
      int sequenceLength,
      float[] rawLogits) {}

  private static List<Case> referenceCases() throws IOException {
    String json;
    try (InputStream stream =
        ModernBertDecisionForwardPassIntegrationTest.class.getResourceAsStream(
            "/modernbert/laya-reference.json")) {
      if (stream == null) {
        throw new IllegalStateException("missing /modernbert/laya-reference.json");
      }
      json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Case> cases = new ArrayList<>();
    Pattern block =
        Pattern.compile(
            "\\{\\s*\"question\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([^\"]+)\".*?"
                + "\"ids\":\\s*\\[([^]]*)].*?"
                + "\"markers\":\\s*\\[([^]]*)].*?"
                + "\"headLength\":\\s*(\\d+).*?"
                + "\"sequenceLength\":\\s*(\\d+).*?"
                + "\"rawLogits\":\\s*\\[([^]]*)]",
            Pattern.DOTALL);
    Matcher matcher = block.matcher(json);
    while (matcher.find()) {
      cases.add(
          new Case(
              matcher.group(1),
              QuestionType.valueOf(matcher.group(2).toUpperCase(Locale.ROOT)),
              ints(matcher.group(3)),
              ints(matcher.group(4)),
              Integer.parseInt(matcher.group(5)),
              Integer.parseInt(matcher.group(6)),
              floats(matcher.group(7))));
    }
    return cases;
  }

  private static int[] ints(String csv) {
    String[] parts = csv.trim().split(",");
    int[] values = new int[parts.length];
    for (int index = 0; index < parts.length; index++) {
      values[index] = Integer.parseInt(parts[index].trim());
    }
    return values;
  }

  private static float[] floats(String csv) {
    String[] parts = csv.trim().split(",");
    float[] values = new float[parts.length];
    for (int index = 0; index < parts.length; index++) {
      values[index] = Float.parseFloat(parts[index].trim());
    }
    return values;
  }
}

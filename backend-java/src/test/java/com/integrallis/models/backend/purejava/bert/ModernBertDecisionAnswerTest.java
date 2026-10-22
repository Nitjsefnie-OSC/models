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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Decoding one typed decision, checked against the reference implementation's probabilities.
 *
 * <p>The fixture carries, per case, the raw per-option logits the reference's head produced and the
 * probabilities it published from them. This test feeds the former through our decoder and compares
 * against the latter, so the temperature lookup, the clamp and the softmax are all checked by the
 * same assertion — the three places this step has to agree with the reference.
 *
 * <p>**Tolerance, fixed before the comparison ran: 1e-6 absolute on each probability.** The
 * reference computes in float32 and so does this decoder; anything looser would stop detecting a
 * wrong temperature, and the clamped bucket moves a probability by tenths.
 */
@Tag("unit")
class ModernBertDecisionAnswerTest {

  private static final float TOLERANCE = 1.0e-6f;

  @Test
  void reproducesTheReferenceProbabilitiesForEveryCase() throws IOException {
    Reference reference = reference();
    assertThat(reference.cases).hasSize(8);

    for (Case expected : reference.cases) {
      ModernBertDecisionAnswer answer =
          ModernBertDecisionAnswer.of(expected.type, expected.logits, Optional.of(reference.head));

      assertThat(answer.temperatureBucket())
          .as("bucket for %s", expected.question)
          .isEqualTo(expected.temperatureBucket);
      assertThat(answer.temperature())
          .as("temperature for %s", expected.question)
          .isEqualTo(expected.temperature);
      assertThat(answer.probabilities())
          .as("probabilities for %s (%d options)", expected.question, expected.logits.length)
          .containsExactly(expected.probabilities, org.assertj.core.data.Offset.offset(TOLERANCE));
      int referenceChoice = argmax(expected.probabilities);
      assertThat(answer.chosen()).as("choice for %s", expected.question).isEqualTo(referenceChoice);
    }
  }

  @Test
  void marksTheBucketTheReferenceRefusesAsUncalibratedAndNoOther() throws IOException {
    Reference reference = reference();

    for (Case expected : reference.cases) {
      ModernBertDecisionAnswer answer =
          ModernBertDecisionAnswer.of(expected.type, expected.logits, Optional.of(reference.head));

      boolean isElevenPlus = expected.logits.length > 10 && expected.type == QuestionType.CHOICE;
      assertThat(answer.uncalibrated())
          .as("%s (%s) uncalibrated", expected.question, answer.temperatureBucket())
          .isEqualTo(isElevenPlus);
    }
  }

  @Test
  void treatsAnArtifactWithNoDeclaredCalibrationAsUncalibratedAtTemperatureOne() {
    ModernBertDecisionAnswer answer =
        ModernBertDecisionAnswer.of(QuestionType.NOUL, new float[] {1.0f, 2.0f}, Optional.empty());

    assertThat(answer.temperature()).isEqualTo(1.0f);
    assertThat(answer.uncalibrated()).isTrue();
    assertThat(answer.chosen()).isEqualTo(1);
    // softmax([1,2]) = [0.26894, 0.73106]
    assertThat(answer.probabilities()[1])
        .isCloseTo(0.7310586f, org.assertj.core.data.Offset.offset(1e-6f));
  }

  @Test
  void reportsConfidenceAndMarginFromTheChosenOption() throws IOException {
    Reference reference = reference();
    Case fourOptions =
        reference.cases.stream().filter(c -> c.logits.length == 4).findFirst().orElseThrow();

    ModernBertDecisionAnswer answer =
        ModernBertDecisionAnswer.of(
            fourOptions.type, fourOptions.logits, Optional.of(reference.head));

    float[] probabilities = answer.probabilities();
    assertThat(answer.confidence()).isEqualTo(probabilities[answer.chosen()]);
    float[] sorted = probabilities.clone();
    java.util.Arrays.sort(sorted);
    assertThat(answer.margin())
        .isCloseTo(
            sorted[sorted.length - 1] - sorted[sorted.length - 2],
            org.assertj.core.data.Offset.offset(1e-6f));
  }

  @Test
  void refusesAQuestionWithNoOptionsAndANonFiniteLogit() {
    assertThatThrownBy(
            () -> ModernBertDecisionAnswer.of(QuestionType.CHOICE, new float[0], Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one option");

    assertThatThrownBy(
            () ->
                ModernBertDecisionAnswer.of(
                    QuestionType.CHOICE, new float[] {1.0f, Float.NaN}, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finite");
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
      String temperatureBucket,
      float temperature,
      float[] logits,
      float[] probabilities) {}

  private record Reference(ModernBertConfig.Decision head, List<Case> cases) {}

  /**
   * Reads the reference fixture, including the declared and clamped temperature maps.
   *
   * <p>Regex rather than a JSON library for the same reason as the layout test: {@code
   * backend-java} ships, and a test fixture is not a reason to put a parser in its dependency
   * graph.
   */
  private static Reference reference() throws IOException {
    String json = fixture();
    Map<String, Float> declared = temperatures(json, "temperatureByOptionsRaw");
    Map<String, Float> clamped = temperatures(json, "temperatureByOptions");
    ModernBertConfig.Decision head =
        new ModernBertConfig.Decision("laya", 2, 192, declared, clamped);

    List<Case> cases = new ArrayList<>();
    Pattern block =
        Pattern.compile(
            "\\{\\s*\"question\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([^\"]+)\".*?"
                + "\"temperatureKey\":\\s*\"([^\"]+)\",\\s*"
                + "\"temperature\":\\s*([0-9.eE+-]+).*?"
                + "\"rawLogits\":\\s*\\[([^]]*)].*?"
                + "\"probabilities\":\\s*\\[([^]]*)]",
            Pattern.DOTALL);
    Matcher matcher = block.matcher(json);
    while (matcher.find()) {
      cases.add(
          new Case(
              matcher.group(1),
              QuestionType.valueOf(matcher.group(2).toUpperCase(Locale.ROOT)),
              matcher.group(3),
              Float.parseFloat(matcher.group(4)),
              floats(matcher.group(5)),
              floats(matcher.group(6))));
    }
    return new Reference(head, cases);
  }

  private static Map<String, Float> temperatures(String json, String field) {
    Matcher object =
        Pattern.compile("\"" + field + "\":\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(json);
    if (!object.find()) {
      throw new IllegalStateException("fixture has no " + field);
    }
    Map<String, Float> values = new LinkedHashMap<>();
    Matcher entry = Pattern.compile("\"([^\"]+)\":\\s*([0-9.eE+-]+)").matcher(object.group(1));
    while (entry.find()) {
      values.put(entry.group(1), Float.parseFloat(entry.group(2)));
    }
    return values;
  }

  private static float[] floats(String csv) {
    String trimmed = csv.trim();
    if (trimmed.isEmpty()) {
      return new float[0];
    }
    String[] parts = trimmed.split(",");
    float[] values = new float[parts.length];
    for (int index = 0; index < parts.length; index++) {
      values[index] = Float.parseFloat(parts[index].trim());
    }
    return values;
  }

  private static String fixture() throws IOException {
    try (InputStream stream =
        ModernBertDecisionAnswerTest.class.getResourceAsStream("/modernbert/laya-reference.json")) {
      if (stream == null) {
        throw new IllegalStateException("missing /modernbert/laya-reference.json");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

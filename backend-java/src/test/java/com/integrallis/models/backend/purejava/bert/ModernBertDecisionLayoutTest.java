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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The parallel option layout, checked against the reference implementation's own output.
 *
 * <p>{@code modernbert/laya-reference.json} was produced by the upstream {@code laya} package at
 * the checkpoint revision {@code ggml-org/Laya-GGUF} was converted from, over eight real questions
 * covering every question type and every option-count bucket. Nothing in this test's expectations
 * was computed by this codebase.
 *
 * <p>The fixture varies what matters: 2, 3, 4, 5, 8 and 12 options, and option spans of unequal
 * width. A fixture with uniformly sized options could not fail on the one thing this layout exists
 * to do, which is to share positions across options of different lengths.
 */
@Tag("unit")
class ModernBertDecisionLayoutTest {

  @Test
  void reproducesTheReferenceLayoutForEveryCase() throws IOException {
    List<Case> cases = referenceCases();
    assertThat(cases).hasSize(8);

    for (Case expected : cases) {
      ModernBertDecisionLayout layout =
          ModernBertDecisionLayout.of(
              expected.markers, expected.headLength, expected.sequenceLength);

      assertThat(layout.positionIds())
          .as("position ids for %s (%d options)", expected.question, expected.markers.length)
          .containsExactly(expected.positionIds);
      assertThat(layout.optionIds())
          .as("option ids for %s (%d options)", expected.question, expected.markers.length)
          .containsExactly(expected.optionIds);
    }
  }

  @Test
  void givesEveryOptionTheSameStartingPosition() throws IOException {
    for (Case expected : referenceCases()) {
      ModernBertDecisionLayout layout =
          ModernBertDecisionLayout.of(
              expected.markers, expected.headLength, expected.sequenceLength);
      int[] positions = layout.positionIds();

      List<Integer> atMarkers = new ArrayList<>();
      for (int marker : expected.markers) {
        atMarkers.add(positions[marker]);
      }
      assertThat(atMarkers)
          .as("every marker of %s shares one position", expected.question)
          .containsOnly(positions[expected.markers[0]]);
    }
  }

  @Test
  void isolatesOptionsFromEachOtherButNotFromTheState() throws IOException {
    Case fourOptions =
        referenceCases().stream().filter(c -> c.markers.length == 4).findFirst().orElseThrow();
    ModernBertDecisionLayout layout =
        ModernBertDecisionLayout.of(
            fourOptions.markers, fourOptions.headLength, fourOptions.sequenceLength);

    int firstOption = fourOptions.markers[0];
    int secondOption = fourOptions.markers[1];
    int stateToken = fourOptions.sequenceLength - 1;
    int instructionToken = 1;

    assertThat(layout.isolates(firstOption, secondOption)).isTrue();
    assertThat(layout.isolates(secondOption, firstOption)).isTrue();
    assertThat(layout.isolates(firstOption, firstOption)).isFalse();
    // The state is what the options are scored against, so it is never isolated from them.
    assertThat(layout.isolates(firstOption, stateToken)).isFalse();
    assertThat(layout.isolates(stateToken, firstOption)).isFalse();
    assertThat(layout.isolates(firstOption, instructionToken)).isFalse();
  }

  @Test
  void laysOutASequenceWithNoOptionsSequentially() {
    ModernBertDecisionLayout layout = ModernBertDecisionLayout.of(new int[0], 1, 5);

    assertThat(layout.positionIds()).containsExactly(0, 1, 2, 3, 4);
    assertThat(layout.optionIds()).containsExactly(0, 0, 0, 0, 0);
    assertThat(layout.options()).isZero();
  }

  @Test
  void refusesMarkersThatDoNotAscend() {
    assertThatThrownBy(() -> ModernBertDecisionLayout.of(new int[] {10, 10}, 20, 40))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ascend");
  }

  @Test
  void refusesAMarkerOutsideTheHead() {
    assertThatThrownBy(() -> ModernBertDecisionLayout.of(new int[] {10, 25}, 20, 40))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inside the head");
  }

  private record Case(
      String question,
      int[] markers,
      int headLength,
      int sequenceLength,
      int[] positionIds,
      int[] optionIds) {}

  /**
   * Reads the reference fixture.
   *
   * <p>Parsed with a regular expression rather than a JSON library because {@code backend-java}
   * deliberately has no JSON dependency, and adding one to read a test fixture would put a
   * dependency in the dependency graph of a module that ships.
   */
  private static List<Case> referenceCases() throws IOException {
    String json = fixture();
    List<Case> cases = new ArrayList<>();
    Pattern block =
        Pattern.compile(
            "\\{\\s*\"question\":\\s*\"([^\"]+)\".*?"
                + "\"markers\":\\s*\\[([^]]*)].*?"
                + "\"headLength\":\\s*(\\d+).*?"
                + "\"sequenceLength\":\\s*(\\d+).*?"
                + "\"positionIds\":\\s*\\[([^]]*)].*?"
                + "\"optionIds\":\\s*\\[([^]]*)]",
            Pattern.DOTALL);
    Matcher matcher = block.matcher(json);
    while (matcher.find()) {
      cases.add(
          new Case(
              matcher.group(1),
              ints(matcher.group(2)),
              Integer.parseInt(matcher.group(3)),
              Integer.parseInt(matcher.group(4)),
              ints(matcher.group(5)),
              ints(matcher.group(6))));
    }
    return cases;
  }

  private static int[] ints(String csv) {
    String trimmed = csv.trim();
    if (trimmed.isEmpty()) {
      return new int[0];
    }
    String[] parts = trimmed.split(",");
    int[] values = new int[parts.length];
    for (int index = 0; index < parts.length; index++) {
      values[index] = Integer.parseInt(parts[index].trim());
    }
    return values;
  }

  private static String fixture() throws IOException {
    try (InputStream stream =
        ModernBertDecisionLayoutTest.class.getResourceAsStream("/modernbert/laya-reference.json")) {
      if (stream == null) {
        throw new IllegalStateException("missing /modernbert/laya-reference.json");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

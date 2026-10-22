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
package com.integrallis.models.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Why the summarization corpus has never qualified a model, settled on recorded generations.
 *
 * <p>The fixture is the fleet's own output: shard 708, the Mixture-Summarizer on the summarization
 * corpus, 2026-10-22, with each case's {@code grounding.rawText} copied verbatim out of the report.
 * Nothing is synthesised and no model runs here, so "which check rejected a complete answer" is
 * answered by evaluating the checks rather than by reading them and guessing.
 *
 * <p>The replay is only worth anything if it reproduces the fleet, so that is asserted first: all
 * nine recorded decisions come back identical. What the walk then shows is that {@link
 * GroundedAnswerPolicy#hasOnlySupportedClaims} is the gate that rejects every one of the five
 * answers that were fully fact-covering with perfect citation recall and precision. **It is not
 * {@code hasEvidenceAnchorsForEveryQuestionClause}**, which was the first hypothesis and is wrong:
 * that predicate never runs on these inputs, because the supported-claim screen returns false
 * first. This test exists so the next change to the policy is aimed at the gate that actually
 * fires.
 */
@Tag("unit")
class SummarizationGroundingReplayTest {

  private record Replay(
      String caseId,
      String question,
      boolean answerable,
      String recordedDecision,
      String rawText,
      boolean rawCorrect,
      double rawFactCoverage,
      List<GroundingDocument> retrieved) {}

  @Test
  void replayReproducesEveryDecisionTheFleetRecorded() throws IOException {
    List<Replay> cases = replays();
    assertThat(cases).hasSize(9);

    Map<String, String> replayed = new LinkedHashMap<>();
    for (Replay replay : cases) {
      GroundedAnswer answer =
          GroundedAnswerPolicy.productionDefault()
              .apply(replay.question(), replay.retrieved(), replay.rawText());
      replayed.put(replay.caseId(), answer.decision().name());
    }
    assertThat(replayed.values())
        .containsExactlyElementsOf(cases.stream().map(Replay::recordedDecision).toList());
  }

  @Test
  void theModelsOwnTextIsFactCompleteOnEveryAnswerableCaseAndCorrectOnSixOfEight()
      throws IOException {
    List<Replay> answerable = replays().stream().filter(Replay::answerable).toList();
    assertThat(answerable).hasSize(8);

    // RagEvaluator's raw evaluation, scored against generation.text() rather than the grounded
    // answer: rawCorrect is true only when factCoverage, citationRecall and citationPrecision are
    // all 1.0 on the text the model actually produced.
    assertThat(answerable).allMatch(replay -> replay.rawFactCoverage() == 1.0);
    assertThat(answerable.stream().filter(Replay::rawCorrect).count()).isEqualTo(6);

    // And one of those six reaches the user as the model's own words.
    assertThat(
            answerable.stream()
                .filter(replay -> "MODEL_ANSWER".equals(replay.recordedDecision()))
                .map(Replay::caseId))
        .containsExactly("summarize-capacity-review");
  }

  @Test
  void theSupportedClaimScreenIsTheGateThatRejectsEveryFactCompleteAnswer() throws IOException {
    GroundedAnswerPolicy policy = GroundedAnswerPolicy.productionDefault();
    Map<String, String> gates = new LinkedHashMap<>();

    for (Replay replay : replays()) {
      if (!replay.answerable() || !replay.rawCorrect()) {
        continue;
      }
      if ("MODEL_ANSWER".equals(replay.recordedDecision())) {
        continue;
      }
      gates.put(replay.caseId(), firstFailingGate(policy, replay));
    }

    assertThat(gates)
        .as("answers that were fact-complete and correctly cited yet never reached the user")
        .hasSize(5);
    assertThat(gates.values())
        .as("the gate each one dies at")
        .containsOnly("hasOnlySupportedClaims");
  }

  @Test
  void theClauseAnchorPredicateIsNotReachedOnAnyOfThem() throws IOException {
    // The hypothesis this test was written to check, recorded as refuted rather than deleted. The
    // walk in firstFailingGate returns at the supported-claim screen, so the anchor predicate's
    // verdict on these inputs never affects the decision -- whatever that verdict is.
    for (Replay replay : replays()) {
      if (!replay.answerable() || !replay.rawCorrect()) {
        continue;
      }
      if ("MODEL_ANSWER".equals(replay.recordedDecision())) {
        continue;
      }
      String validation = validationCandidate(replay);
      assertThat(
              GroundedAnswerPolicy.hasOnlySupportedClaims(
                  replay.question(), validation, replay.retrieved()))
          .as("%s is already rejected before the anchor predicate runs", replay.caseId())
          .isFalse();
    }
  }

  @Test
  void theAnswersStillCarryTheirIdentifiersAndAreNotAbstentions() throws IOException {
    // Two checks that are right for any grounded answer and that these answers pass, so a fix to
    // the supported-claim screen must not be mistaken for a fix to either of them.
    for (Replay replay : replays()) {
      if (!replay.answerable() || !replay.rawCorrect()) {
        continue;
      }
      String candidate = GroundedAnswerPolicy.removeCompleteReasoningTrace(replay.rawText().strip());
      assertThat(GroundedAnswerPolicy.isExplicitAbstention(candidate))
          .as("%s is not an abstention", replay.caseId())
          .isFalse();
      assertThat(
              GroundedAnswerPolicy.preservesAtomicIdentifiers(
                  validationCandidate(replay), replay.retrieved()))
          .as("%s preserves atomic identifiers", replay.caseId())
          .isTrue();
    }
  }

  @Test
  void theWordsTheScreenCallsUnsupportedSeparateThreeDifferentCauses() throws IOException {
    // What `hasOnlySupportedClaims` actually asks: that EVERY content word of the answer appear,
    // modulo normalisation, in the question or a retrieved document (GroundedAnswerPolicy:352-362).
    // So the words it cannot find are the whole diagnosis, and recorded here per case they show
    // that "the policy rejects good summaries" is three separate things, not one:
    //
    //   1. PARAPHRASE. summarize-incident-4417 dies on `caused`, `due`, `loss`; summarize-release-2-8
    //      on the single word `reduced`. These are a summary's own vocabulary. A bag-of-words
    //      containment screen cannot admit a paraphrase, which is the real mismatch between an
    //      extractive-QA screen and a summarization workload.
    //   2. A LEAKED REASONING TRACE. summarize-incident-4502 and summarize-retention come back with
    //      `think`, `draft`, `citation`, `format`, `instruction`, `sentence`, `user`. That is the
    //      model's scratchpad reaching the validator, so `removeCompleteReasoningTrace` did not
    //      strip this output shape. Here the screen is doing its job; the bug is upstream of it.
    //   3. A DERIVED NUMBER. summarize-access-review answers "17 accounts" from documents that say
    //      14 and 3. The evidence never states 17, so rejecting it is CORRECT, and whether a
    //      summarization policy may do arithmetic over its evidence is a decision to take on
    //      purpose rather than a gate to loosen by accident.
    Map<String, List<String>> expected = new LinkedHashMap<>();
    expected.put("summarize-incident-4417", List.of("caused", "due", "loss"));
    expected.put("summarize-release-2-8", List.of("reduced"));
    expected.put("summarize-access-review", List.of("17", "change", "identified", "requiring"));
    expected.put("summarize-release-2-7", List.of("increas", "introduce", "while"));
    expected.put("summarize-deploy-policy", List.of("can", "while"));

    Map<String, List<String>> traceLeak = new LinkedHashMap<>();
    for (Replay replay : replays()) {
      if (!replay.answerable()) {
        continue;
      }
      List<String> unsupported = unsupportedWords(replay);
      if (expected.containsKey(replay.caseId())) {
        assertThat(unsupported)
            .as("words %s introduces that the evidence does not carry", replay.caseId())
            .containsExactlyElementsOf(expected.get(replay.caseId()));
      } else if (!unsupported.isEmpty()) {
        traceLeak.put(replay.caseId(), unsupported);
      }
    }

    assertThat(traceLeak.keySet())
        .as("the cases whose reasoning trace survived into the validated candidate")
        .containsExactly("summarize-incident-4502", "summarize-retention");
    // The tell is meta-vocabulary about the act of answering, which no summary of an incident
    // report would contain.
    assertThat(traceLeak.values())
        .allSatisfy(words -> assertThat(words).contains("think", "citation", "format", "sentence"));
  }

  /** The words {@link GroundedAnswerPolicy#hasOnlySupportedClaims} cannot find in the evidence. */
  private static List<String> unsupportedWords(Replay replay) {
    Set<String> supported = GroundedAnswerPolicy.words(replay.question());
    for (GroundingDocument document : replay.retrieved()) {
      supported.addAll(GroundedAnswerPolicy.words(document.title()));
      supported.addAll(GroundedAnswerPolicy.words(document.text()));
    }
    String uncited = validationCandidate(replay).replaceAll("\\[[^]]*]", " ");
    return GroundedAnswerPolicy.words(uncited).stream()
        .filter(word -> !GroundedAnswerPolicy.containsEquivalentWord(supported, word))
        .sorted()
        .toList();
  }

  private static String validationCandidate(Replay replay) {
    String candidate = GroundedAnswerPolicy.removeCompleteReasoningTrace(replay.rawText().strip());
    return GroundedAnswerPolicy.removeValidationOnlyMarkup(
        GroundedAnswerPolicy.removeLeadingContextAttribution(candidate));
  }

  /**
   * Walks {@link GroundedAnswerPolicy#apply}'s gates in its own order and names the first that
   * refuses, or says the answer was admitted.
   *
   * <p>The gates are called rather than reimplemented, so this cannot drift from what {@code apply}
   * does; the only thing duplicated is the order, and the fidelity test is what catches a mistake
   * in that.
   */
  private static String firstFailingGate(GroundedAnswerPolicy policy, Replay replay) {
    String question = replay.question();
    List<GroundingDocument> retrieved = replay.retrieved();
    if (!policy.assess(question, retrieved).generationAllowed()) {
      return "assess: " + policy.assess(question, retrieved);
    }
    String candidate = GroundedAnswerPolicy.removeCompleteReasoningTrace(replay.rawText().strip());
    if (GroundedAnswerPolicy.isExplicitAbstention(candidate)) {
      return "isExplicitAbstention";
    }
    String validation =
        GroundedAnswerPolicy.removeValidationOnlyMarkup(
            GroundedAnswerPolicy.removeLeadingContextAttribution(candidate));
    if (validation.isBlank()) {
      return "blank after pre-processing";
    }
    if (!GroundedAnswerPolicy.preservesAtomicIdentifiers(validation, retrieved)) {
      return "preservesAtomicIdentifiers";
    }
    if (!GroundedAnswerPolicy.hasOnlySupportedClaims(question, validation, retrieved)) {
      return "hasOnlySupportedClaims";
    }
    if (!GroundedAnswerPolicy.hasEvidenceAnchorsForEveryQuestionClause(
        question, validation, retrieved)) {
      return "hasEvidenceAnchorsForEveryQuestionClause";
    }
    List<GroundingDocument> supporting =
        GroundedAnswerPolicy.supportingDocuments(question, validation, retrieved, retrieved);
    if (supporting.isEmpty()) {
      return "supportingDocuments(all) empty";
    }
    if (!GroundedAnswerPolicy.hasCitations(candidate)) {
      return "none; admitted with derived citations";
    }
    if (!GroundedAnswerPolicy.hasOnlyTrustedCitations(candidate, retrieved)) {
      return "hasOnlyTrustedCitations";
    }
    List<GroundingDocument> cited = GroundedAnswerPolicy.citedDocuments(candidate, retrieved);
    if (GroundedAnswerPolicy.supportingDocuments(question, validation, retrieved, cited).isEmpty()) {
      return "supportingDocuments(cited) empty";
    }
    return "none; admitted as MODEL_ANSWER";
  }

  private static List<Replay> replays() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root;
    try (InputStream stream =
        SummarizationGroundingReplayTest.class.getResourceAsStream(
            "/grounding/summarization-replay.json")) {
      if (stream == null) {
        throw new IllegalStateException("missing /grounding/summarization-replay.json");
      }
      root = mapper.readTree(stream);
    }
    List<Replay> cases = new ArrayList<>();
    for (JsonNode node : root.get("cases")) {
      List<GroundingDocument> retrieved = new ArrayList<>();
      for (JsonNode hit : node.get("retrieved")) {
        retrieved.add(
            new GroundingDocument(
                hit.get("id").asText(),
                hit.get("title").asText(),
                hit.get("text").asText(),
                (float) hit.get("score").asDouble(),
                hit.get("rank").asInt()));
      }
      JsonNode raw = node.get("rawEvaluation");
      cases.add(
          new Replay(
              node.get("caseId").asText(),
              node.get("question").asText(),
              node.get("answerable").asBoolean(),
              node.get("recordedDecision").asText(),
              node.get("rawText").asText(),
              raw.get("correct").asBoolean(),
              raw.get("factCoverage").asDouble(),
              retrieved));
    }
    return cases;
  }
}

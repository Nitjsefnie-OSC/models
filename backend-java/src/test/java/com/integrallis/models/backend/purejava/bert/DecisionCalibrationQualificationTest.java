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

import com.integrallis.models.backend.purejava.bert.DecisionCalibrationQualification.Outcome;
import com.integrallis.models.backend.purejava.bert.DecisionCalibrationQualification.Report;
import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gate itself, checked on constructed cohorts before it is pointed at a model.
 *
 * <p>A policy that has only ever been run on real output is a policy nobody has seen fail. Each
 * gate here is driven to both sides, because the whole value of a threshold is that it refuses
 * something.
 */
@Tag("unit")
class DecisionCalibrationQualificationTest {

  @Test
  void qualifiesACohortThatClearsEveryGate() {
    List<Outcome> cohort = new ArrayList<>();
    // 40 four-way choices at 0.85 confidence, 34 right: accuracy 0.85, confidence 0.85.
    cohort.addAll(choice(34, true, 0.85));
    cohort.addAll(choice(6, false, 0.85));
    // 20 binary noul at 0.80 confidence, 16 right.
    cohort.addAll(noul(16, true, 0.80));
    cohort.addAll(noul(4, false, 0.80));

    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.qualified()).as("%s", report.failures()).isTrue();
    assertThat(report.policyId()).isEqualTo("decision-calibration-v1");
    assertThat(report.accuracy()).isEqualTo(0.8333, org.assertj.core.data.Offset.offset(1e-4));
    // Chance from the option counts actually present: 40 at 1/4 and 20 at 1/2.
    assertThat(report.chanceAccuracy()).isEqualTo(0.3333, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(report.accuracyByType()).containsEntry("choice", 0.85).containsEntry("noul", 0.80);
  }

  @Test
  void refusesAStrongMultiWaySubsetCarryingAWeakBinaryOne() {
    // The reason the per-type gates exist. Overall accuracy clears 0.70 while the binary subset
    // sits at 0.55 -- barely above its own 0.50 chance, and useless to a caller.
    List<Outcome> cohort = new ArrayList<>();
    cohort.addAll(choice(76, true, 0.8));
    cohort.addAll(choice(4, false, 0.8));
    cohort.addAll(noul(11, true, 0.6));
    cohort.addAll(noul(9, false, 0.6));

    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.accuracy()).isGreaterThan(DecisionCalibrationQualification.MINIMUM_ACCURACY);
    assertThat(report.qualified()).isFalse();
    assertThat(report.failures()).anyMatch(failure -> failure.startsWith("noul accuracy"));
    assertThat(report.chanceByType()).containsEntry("noul", 0.5);
  }

  @Test
  void refusesConfidenceThatDoesNotMatchAccuracy() {
    // Right 60% of the time while claiming 0.95 every time: the exact failure the gate exists for,
    // and one that accuracy alone cannot see.
    List<Outcome> cohort = new ArrayList<>();
    cohort.addAll(choice(60, true, 0.95));
    cohort.addAll(choice(40, false, 0.95));

    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.calibrationError())
        .isEqualTo(0.35, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(report.qualified()).isFalse();
    assertThat(report.failures()).anyMatch(failure -> failure.startsWith("calibration error"));
  }

  @Test
  void excludesAnswersThatDeclaredThemselvesUncalibrated() {
    // Honesty must not be scored as error. These answers say their probability is not one anybody
    // fitted, so including them in the calibration term would penalise exactly the behaviour the
    // policy asks for.
    List<Outcome> honest = new ArrayList<>();
    honest.addAll(choice(34, true, 0.85));
    honest.addAll(choice(6, false, 0.85));
    honest.add(new Outcome("wild", QuestionType.CHOICE, 12, false, 0.99, true));

    Report report = DecisionCalibrationQualification.assess(honest);

    assertThat(report.uncalibratedAnswers()).isEqualTo(1);
    assertThat(report.uncalibratedBucketsExercised()).isEqualTo(1);
    assertThat(report.failures()).noneMatch(failure -> failure.startsWith("calibration error"));
  }

  @Test
  void refusesAnUncalibratedBucketThatPublishedAProbabilityAnyway() {
    List<Outcome> cohort = new ArrayList<>(choice(32, true, 0.8));
    cohort.addAll(choice(8, false, 0.8));
    cohort.add(new Outcome("twelve-way", QuestionType.CHOICE, 12, true, 0.99, false));

    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.uncalibratedBucketsExercised()).isEqualTo(1);
    assertThat(report.qualified()).isFalse();
    assertThat(report.failures())
        .anyMatch(failure -> failure.contains("declares uncalibrated"));
  }

  @Test
  void reportsZeroExercisedWhenTheCohortCannotReachAnUncalibratedBucket() {
    // JevBench tops out at six options, so this is the case that will actually occur. A pass has
    // to be legible as "never tested" rather than as evidence.
    //
    // The cohort is calibrated on purpose -- 32 of 40 right while claiming 0.80 -- because an
    // all-correct cohort at 0.80 is UNDERconfident and the calibration gate rejects it. It did
    // exactly that when this fixture was first written, which is the gate working.
    List<Outcome> cohort = new ArrayList<>(choice(32, true, 0.8));
    cohort.addAll(choice(8, false, 0.8));
    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.uncalibratedBucketsExercised()).isZero();
    assertThat(report.qualified()).isTrue();
  }

  @Test
  void reportsNotANumberWhenEveryAnswerWasUncalibrated() {
    // Zero would read as perfect calibration, which is the opposite of what no data means.
    List<Outcome> cohort =
        List.of(
            new Outcome("a", QuestionType.CHOICE, 12, true, 0.9, true),
            new Outcome("b", QuestionType.CHOICE, 12, false, 0.9, true));

    Report report = DecisionCalibrationQualification.assess(cohort);

    assertThat(report.calibrationError()).isNaN();
  }

  @Test
  void refusesAnEmptyCohort() {
    assertThatThrownBy(() -> DecisionCalibrationQualification.assess(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty cohort");
  }

  private static List<Outcome> choice(int count, boolean correct, double confidence) {
    return repeat("choice", QuestionType.CHOICE, 4, count, correct, confidence);
  }

  private static List<Outcome> noul(int count, boolean correct, double confidence) {
    return repeat("noul", QuestionType.NOUL, 2, count, correct, confidence);
  }

  private static List<Outcome> repeat(
      String prefix, QuestionType type, int options, int count, boolean correct, double confidence) {
    List<Outcome> outcomes = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      outcomes.add(
          new Outcome(
              prefix + "-" + correct + "-" + index, type, options, correct, confidence, false));
    }
    return outcomes;
  }
}

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

import com.integrallis.models.backend.purejava.bert.DecisionCalibrationQualification.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evidence writer, checked on constructed measurements without loading a model.
 *
 * <p>A digest must identify the measurements even when another run takes longer or presents its
 * maps in a different order. A changed measurement must still change that identity.
 */
@Tag("unit")
class LayaJevBenchQualificationReportTest {

  @TempDir Path directory;

  @Test
  void reproducesEvidenceAcrossElapsedTimesAndMapOrders() throws IOException {
    Report firstReport = report(List.of("Aa", "BB"), 0.8);
    Report secondReport = report(List.of("BB", "Aa"), 0.8);
    // Colliding keys keep Map.copyOf from masking the insertion-order difference in one JVM.
    assertThat(new ArrayList<>(firstReport.accuracyByType().keySet()))
        .isNotEqualTo(new ArrayList<>(secondReport.accuracyByType().keySet()));

    Path first = write("first.json", firstReport, 489.12, List.of("    {\"confidence\":0.8}"));
    Path second = write("second.json", secondReport, 476.12, List.of("    {\"confidence\":0.8}"));

    assertThat(Files.readAllBytes(second))
        .as("digested evidence bytes")
        .isEqualTo(Files.readAllBytes(first));
    assertThat(LayaJevBenchQualificationIT.sha256(second))
        .as("reportSha256")
        .isEqualTo(LayaJevBenchQualificationIT.sha256(first));
  }

  @Test
  void changesTheDigestWhenASummaryMeasurementChanges() throws IOException {
    Path first = write("first.json", report(List.of("Aa", "BB"), 0.8), 1, List.of());
    Path changed = write("changed.json", report(List.of("Aa", "BB"), 0.7), 1, List.of());

    assertThat(LayaJevBenchQualificationIT.sha256(changed))
        .isNotEqualTo(LayaJevBenchQualificationIT.sha256(first));
  }

  @Test
  void changesTheDigestWhenAPerCaseMeasurementChanges() throws IOException {
    Report report = report(List.of("Aa", "BB"), 0.8);
    Path first = write("first.json", report, 1, List.of("    {\"confidence\":0.8}"));
    Path changed = write("changed.json", report, 1, List.of("    {\"confidence\":0.9}"));

    assertThat(LayaJevBenchQualificationIT.sha256(changed))
        .isNotEqualTo(LayaJevBenchQualificationIT.sha256(first));
  }

  @Test
  void keepsElapsedTimeInAnUndigestedSiblingReport() throws IOException {
    Path evidence = write("report.json", report(List.of("Aa", "BB"), 0.8), 489.12, List.of());
    Path timing = directory.resolve("report.json.timing.json");

    assertThat(timing).exists();
    assertThat(Files.readString(timing)).contains("\"wallClockSeconds\": 489.12");
    assertThat(Files.readString(evidence))
        .doesNotContain("wallClockSeconds")
        .contains("\"environment\"", "\"artifactSha256\"", "\"cohortSha256\"");
  }

  private Path write(String name, Report report, double seconds, List<String> perCase)
      throws IOException {
    Path artifact = directory.resolve("artifact.gguf");
    Path cohort = directory.resolve("cohort.jsonl");
    Files.writeString(artifact, "same artifact");
    Files.writeString(cohort, "same cohort");
    Path destination = directory.resolve(name);
    LayaJevBenchQualificationIT.writeReport(
        destination, report, perCase, seconds, artifact, cohort);
    return destination;
  }

  private static Report report(List<String> types, double accuracy) {
    Map<String, Double> accuracyByType = new LinkedHashMap<>();
    Map<String, Integer> casesByType = new LinkedHashMap<>();
    Map<String, Double> chanceByType = new LinkedHashMap<>();
    for (String type : types) {
      accuracyByType.put(type, type.equals("Aa") ? 0.85 : 0.75);
      casesByType.put(type, 20);
      chanceByType.put(type, type.equals("Aa") ? 0.25 : 0.5);
    }
    return new Report(
        DecisionCalibrationQualification.POLICY_ID,
        40,
        0.375,
        accuracy,
        accuracyByType,
        casesByType,
        chanceByType,
        0.05,
        0,
        0,
        DecisionUseCaseTier.CALIBRATED_DECISION,
        List.of(),
        List.of());
  }
}

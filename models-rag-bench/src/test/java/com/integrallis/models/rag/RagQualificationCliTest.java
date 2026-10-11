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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagQualificationCliTest {

  private static final Path REPOSITORY_ROOT = Path.of(System.getProperty("models.repositoryRoot"));
  private static final Path EVIDENCE_ROOT =
      REPOSITORY_ROOT.resolve(
          "benchmark-results/certified-20260724/rag/native-q8-prefix-cache/" + "smollm2-360m-q8_0");

  @Test
  void emitsAuditableQualificationEvidenceForCertifiedReports(@TempDir Path temporaryDirectory)
      throws Exception {
    Path candidate = EVIDENCE_ROOT.resolve("smollm2-360m-rust-ffm-prefix-cache-grounded.json");
    List<Path> comparators =
        List.of(
            EVIDENCE_ROOT.resolve("smollm2-360m-llama-current-grounded.json"),
            EVIDENCE_ROOT.resolve("smollm2-360m-ollama-current-grounded.json"));
    Path output = temporaryDirectory.resolve("qualification.json");

    RagQualificationEvidence evidence =
        RagQualificationCli.run(
            new String[] {
              "--candidate",
              candidate.toString(),
              "--comparator",
              comparators.get(0).toString(),
              "--comparator",
              comparators.get(1).toString(),
              "--output",
              output.toString(),
              "--require-qualified"
            });

    assertThat(evidence.schemaVersion()).isEqualTo(1);
    assertThat(evidence.policyId()).isEqualTo(RagProductionQualificationPolicy.POLICY_ID);
    assertThat(evidence.candidate().path()).isEqualTo(candidate.toString());
    assertThat(evidence.candidate().sha256()).hasSize(64);
    assertThat(evidence.comparators())
        .extracting(RagQualificationReportReference::path)
        .containsExactlyElementsOf(comparators.stream().map(Path::toString).toList());
    assertThat(evidence.qualification().qualified()).isTrue();
    assertThat(evidence.qualification().qualifyingComparators()).containsExactly("ollama");
    assertThat(evidence.qualification().comparisons())
        .extracting(RagComparatorAssessment::comparatorBackend)
        .containsExactly("llama.cpp", "ollama");
    assertThat(new ObjectMapper().readValue(output.toFile(), RagQualificationEvidence.class))
        .isEqualTo(evidence);
  }

  @Test
  void publisherPolicyIsExplicitAndEmitsItsOwnIdentity(@TempDir Path directory) throws Exception {
    var mapper = new ObjectMapper();
    var reference = RagPublisherReference.BONSAI2_PTQ1_0;
    var candidate =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(
                EVIDENCE_ROOT.resolve("smollm2-360m-rust-ffm-prefix-cache-grounded.json").toFile());
    candidate.put("modelId", reference.modelId());
    candidate.put("artifactSha256", reference.artifactSha256());
    candidate.put("artifactSizeBytes", reference.artifactSizeBytes());
    // This is synthetic policy input; it deliberately reuses metrics to test the exact boundary.
    var comparator = candidate.deepCopy();
    comparator.put("backend", "llama.cpp");
    comparator.put("backendVersion", reference.backendVersion());
    Path candidateFile = directory.resolve("candidate.json");
    Path comparatorFile = directory.resolve("comparator.json");
    mapper.writeValue(candidateFile.toFile(), candidate);
    mapper.writeValue(comparatorFile.toFile(), comparator);
    var evidence =
        RagQualificationCli.run(
            new String[] {
              "--candidate", candidateFile.toString(), "--comparator", comparatorFile.toString(),
              "--publisher-reference", reference.id(), "--output",
                  directory.resolve("qualification.json").toString(),
              "--require-qualified"
            });
    assertThat(evidence.policyId()).isEqualTo(RagProductionQualificationPolicy.PUBLISHER_POLICY_ID);
    assertThat(evidence.qualification().qualified()).isTrue();
    assertThat(evidence.qualification().qualifyingComparators()).containsExactly("llama.cpp");
  }

  @Test
  void rejectsMissingComparatorEvidence() {
    assertThatThrownBy(
            () ->
                RagQualificationCli.run(
                    new String[] {"--candidate", EVIDENCE_ROOT.resolve("missing.json").toString()}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--comparator");
  }
}

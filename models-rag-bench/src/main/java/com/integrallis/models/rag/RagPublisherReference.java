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

import java.util.Arrays;

/** Explicitly reviewed publisher references for formats unavailable in the ordinary comparators. */
public enum RagPublisherReference {
  /** Prism's exact PTQ1_0 artifact and its publisher-owned CPU implementation. */
  BONSAI2_PTQ1_0(
      "prism-bonsai2-ptq1_0",
      "prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0",
      "53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3",
      5_946_648_928L,
      "https://github.com/PrismML-Eng/llama.cpp",
      "e8fd209c975c80aa7fb36a8423571ff3b1b7e955");

  private final String id;
  private final String modelId;
  private final String artifactSha256;
  private final long artifactSizeBytes;
  private final String repository;
  private final String revision;

  RagPublisherReference(
      String id,
      String modelId,
      String artifactSha256,
      long artifactSizeBytes,
      String repository,
      String revision) {
    this.id = id;
    this.modelId = modelId;
    this.artifactSha256 = artifactSha256;
    this.artifactSizeBytes = artifactSizeBytes;
    this.repository = repository;
    this.revision = revision;
  }

  public String id() {
    return id;
  }

  public String modelId() {
    return modelId;
  }

  public String artifactSha256() {
    return artifactSha256;
  }

  public long artifactSizeBytes() {
    return artifactSizeBytes;
  }

  public String repository() {
    return repository;
  }

  public String revision() {
    return revision;
  }

  public String backendVersion() {
    return "PrismML-Eng/llama.cpp@" + revision;
  }

  boolean matchesArtifact(RagBenchmarkReport report) {
    return modelId.equals(report.modelId())
        && artifactSha256.equals(report.artifactSha256())
        && artifactSizeBytes == report.artifactSizeBytes();
  }

  boolean matchesComparator(RagBenchmarkReport report) {
    return matchesArtifact(report)
        && "llama.cpp".equals(report.backend())
        && backendVersion().equals(report.backendVersion());
  }

  public static RagPublisherReference fromId(String id) {
    return Arrays.stream(values())
        .filter(reference -> reference.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown publisher reference: " + id));
  }
}

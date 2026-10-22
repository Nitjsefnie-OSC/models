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
package com.integrallis.models.backend.purejava;

import static org.assertj.core.api.Assertions.assertThat;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.catalog.DiscoveredModel;
import com.integrallis.models.api.catalog.ModelCatalogProvider;
import com.integrallis.models.backend.purejava.fixture.ModelFixtureRegistry;
import com.integrallis.models.backend.purejava.fixture.ModelFixtureRequirement;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.TextGenerationSession;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-weight gate: a catalog id goes in, generated tokens come out.
 *
 * <p>The unit tests settle which catalog wins and how each refusal reads, but they stop at the
 * resolution and never open anything -- so on their own they would leave the actual claim of this
 * feature untested. This one drives the whole path with a provider that really implements {@link
 * ModelCatalogProvider#locate} and a real artifact, and asserts the decoder ran.
 *
 * <p>That matters because the one shipping catalog, ModelJars, cannot implement {@code locate} yet:
 * it builds against published {@code models 0.5.2}, where the method does not exist. Without this
 * test the feature would be a contract with no implementation behind it on either side.
 */
@Tag("integration")
class CatalogBackendsIntegrationTest {

  private static final ModelFixtureRequirement QWEN3_0_6B_Q4_0 =
      ModelFixtureRequirement.of("hf://ggml-org/Qwen3-0.6B-GGUF")
          .version("[3.0.0,4.0.0)")
          .variant("q4_0")
          .backend("pure-java")
          .capability("text-generation");

  @Test
  void opensAndGeneratesFromAModelNamedOnlyByItsCatalogId() throws Exception {
    Path artifact =
        ModelFixtureRegistry.fromClasspath()
            .resolve(QWEN3_0_6B_Q4_0)
            .orElseThrow()
            .localPath()
            .orElseThrow();
    // The provider is the only thing standing in for ModelJars here. Everything after `locate`
    // returns is the shipped path.
    Iterable<ModelCatalogProvider> catalogs = List.of(catalog("fixture", "qwen3_0_6b_q4_0", artifact));

    assertThat(CatalogBackends.openable(catalogs)).containsExactly("qwen3_0_6b_q4_0");

    var prompt = ChatTemplate.CHATML_NO_THINK.render(List.of(ChatMessage.user("Say READY")));
    var options = SamplingOptions.builder().temperature(0).maxTokens(4).build();

    String answer;
    try (PureJavaBackend backend =
            CatalogBackends.open(catalogs, "qwen3_0_6b_q4_0", BackendConfiguration.empty());
        InferencePipeline pipeline = new InferencePipeline(backend);
        TextGenerationSession session = pipeline.openGenerationSession()) {
      // Same assertion a path-opened backend would get, which is the point: resolution through the
      // catalog changes where the weights were named, and nothing else.
      assertThat(backend.metadata().contextLength()).isPositive();
      answer = session.generate(prompt, options);
    }

    // Greedy, so this is deterministic run to run; what is asserted is that the decoder produced
    // tokens from weights nobody named by path.
    assertThat(answer).isNotBlank();
  }

  private static ModelCatalogProvider catalog(String name, String id, Path artifact) {
    return new ModelCatalogProvider() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<DiscoveredModel> discover() {
        return List.of(
            new DiscoveredModel(
                id,
                true,
                Set.of("chat"),
                Set.of("chat"),
                32768,
                0.0,
                0.0,
                artifact.toFile().length(),
                new DiscoveredModel.Performance(100L, 20.0),
                Map.of(),
                1.0));
      }

      @Override
      public Optional<Path> locate(String requested) {
        return id.equals(requested) ? Optional.of(artifact) : Optional.empty();
      }
    };
  }
}

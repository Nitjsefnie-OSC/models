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

import com.integrallis.models.backend.purejava.gguf.GgufFile;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import com.integrallis.models.test.NanoGgufModel;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code modern-bert} tensor contract, loaded from a fixture shaped like the real artifact.
 *
 * <p>The fixture is deliberately heterogeneous — block 0 without an {@code attn_norm}, two decision
 * blocks carrying biases and a plain feed-forward, two feed-forward widths, four encoder blocks
 * against a window pattern of 3. Every one of those is a thing a uniform fixture holds constant and
 * therefore cannot fail on, and three of them were only visible by triaging the real file rather
 * than reading the model card.
 */
@Tag("unit")
class ModernBertWeightsTest {

  @Test
  void loadsEveryBlockKindFromTheFixture(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
      ModernBertWeights weights = ModernBertWeights.load(file, config);

      assertThat(weights.layerCount())
          .isEqualTo(NanoGgufModel.MODERN_ENCODER_BLOCKS + NanoGgufModel.MODERN_DECISION_BLOCKS);
      assertThat(weights.tokenEmbeddingNorm()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(weights.outputNorm()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(weights.scorer()).isPresent();
    }
  }

  @Test
  void readsBlockZeroWithoutAnAttentionNorm(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertWeights weights =
          ModernBertWeights.load(file, ModernBertConfig.fromMetadata(file.metadata()));

      // ModernBERT normalises the embeddings once and the first block's attention reads that.
      // Demanding an attn_norm for every block would fail to load the real artifact.
      assertThat(weights.layer(0).attentionNormWeight()).isNull();
      assertThat(weights.layer(1).attentionNormWeight()).hasSize(NanoGgufModel.MODERN_DIMENSION);
    }
  }

  @Test
  void separatesGatedEncoderBlocksFromBiasCarryingDecisionBlocks(@TempDir Path directory)
      throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertWeights weights =
          ModernBertWeights.load(file, ModernBertConfig.fromMetadata(file.metadata()));

      ModernBertWeights.Layer encoder = weights.layer(1);
      assertThat(encoder.gated()).isTrue();
      assertThat(encoder.intermediateWidth()).isEqualTo(NanoGgufModel.MODERN_INTERMEDIATE);
      // The gate is only visible in the file as a doubled ffn_up width.
      assertThat(encoder.feedForwardUp().rows()).isEqualTo(2 * NanoGgufModel.MODERN_INTERMEDIATE);
      assertThat(encoder.feedForwardDown().columns()).isEqualTo(NanoGgufModel.MODERN_INTERMEDIATE);
      assertThat(encoder.queryKeyValueBias()).isNull();
      assertThat(encoder.feedForwardUpBias()).isNull();
      assertThat(encoder.attentionNormBias()).isNull();

      ModernBertWeights.Layer decision = weights.layer(NanoGgufModel.MODERN_ENCODER_BLOCKS);
      assertThat(decision.gated()).isFalse();
      assertThat(decision.intermediateWidth()).isEqualTo(NanoGgufModel.MODERN_DECISION_FFN);
      assertThat(decision.feedForwardUp().rows()).isEqualTo(NanoGgufModel.MODERN_DECISION_FFN);
      assertThat(decision.queryKeyValueBias()).hasSize(3 * NanoGgufModel.MODERN_DIMENSION);
      assertThat(decision.attentionOutputBias()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(decision.feedForwardNormBias()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(decision.feedForwardUpBias()).hasSize(NanoGgufModel.MODERN_DECISION_FFN);
      assertThat(decision.feedForwardDownBias()).hasSize(NanoGgufModel.MODERN_DIMENSION);
    }
  }

  @Test
  void readsTheFusedQkvAndTheScorerInTheReferenceOrder(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertWeights weights =
          ModernBertWeights.load(file, ModernBertConfig.fromMetadata(file.metadata()));

      assertThat(weights.layer(0).queryKeyValue().rows())
          .isEqualTo(3 * NanoGgufModel.MODERN_DIMENSION);
      assertThat(weights.layer(0).queryKeyValue().columns())
          .isEqualTo(NanoGgufModel.MODERN_DIMENSION);

      // norm, then the square projection, then the single-logit projection: the order the
      // checkpoint trained, which is not the order llama.cpp's reranker path applies.
      ModernBertWeights.Scorer scorer = weights.scorer().orElseThrow();
      assertThat(scorer.normWeight()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(scorer.normBias()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(scorer.dense().rows()).isEqualTo(NanoGgufModel.MODERN_DIMENSION);
      assertThat(scorer.dense().columns()).isEqualTo(NanoGgufModel.MODERN_DIMENSION);
      assertThat(scorer.denseBias()).hasSize(NanoGgufModel.MODERN_DIMENSION);
      assertThat(scorer.output()).hasSize(NanoGgufModel.MODERN_DIMENSION);
    }
  }

  @Test
  void readsOneTypeEmbeddingRowPerQuestionType(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertWeights weights =
          ModernBertWeights.load(file, ModernBertConfig.fromMetadata(file.metadata()));

      float[] choice = new float[NanoGgufModel.MODERN_DIMENSION];
      float[] noul = new float[NanoGgufModel.MODERN_DIMENSION];
      weights.questionTypeEmbedding(ModernBertConfig.QuestionType.CHOICE.row(), choice);
      weights.questionTypeEmbedding(ModernBertConfig.QuestionType.NOUL.row(), noul);

      // Three distinct rows, and reading the wrong one conditions the head on the wrong question
      // kind — which produces a plausible answer to a question nobody asked.
      assertThat(choice).isNotEqualTo(noul);
      assertThatThrownBy(
              () -> weights.questionTypeEmbedding(NanoGgufModel.MODERN_TOKEN_TYPES, choice))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("out of range");
    }
  }

  @Test
  void claimsEveryTensorTheArtifactCarries(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);

      // "Every shape matched" is weaker than it sounds: a loader can satisfy every assertion and
      // still ignore a tensor the forward pass needs, which is a wrong answer rather than a
      // failure. So the question is asked directly.
      assertThat(ModernBertWeights.unclaimedTensors(file)).isEmpty();
    }
  }

  @Test
  void namesTheTensorWhenAShapeIsWrong(@TempDir Path directory) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      GgufFile file = open(directory, arena);
      ModernBertConfig real = ModernBertConfig.fromMetadata(file.metadata());
      // Claim the decision blocks gate, which doubles the ffn_up width the loader demands.
      ModernBertConfig wrong =
          new ModernBertConfig(
              real.architecture(),
              real.embeddingDim(),
              real.numLayers(),
              real.numHeads(),
              real.vocabSize(),
              real.contextLength(),
              real.feedForwardLengths(),
              real.layerNormEps(),
              real.ropeTheta(),
              real.ropeThetaSlidingWindow(),
              real.slidingWindow(),
              real.slidingWindowPattern(),
              real.pooling(),
              java.util.Optional.empty());

      assertThatThrownBy(() -> ModernBertWeights.load(file, wrong))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ffn_up.weight shape must be");
    }
  }

  private static GgufFile open(Path directory, Arena arena) throws IOException {
    Path artifact =
        NanoGgufModel.writeModernBertDecision(directory.resolve("nano-modern-bert.gguf"));
    return GgufParser.parse(artifact, arena);
  }
}

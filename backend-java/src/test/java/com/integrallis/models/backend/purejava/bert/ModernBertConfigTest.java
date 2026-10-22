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

import com.integrallis.models.backend.purejava.gguf.GgufMetadata;
import com.integrallis.models.backend.purejava.gguf.GgufMetadataValue;
import com.integrallis.models.backend.purejava.gguf.GgufValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The {@code modern-bert} contract, exercised with the header of the artifact it exists for.
 *
 * <p>Every number in {@link #layaMetadata()} was read from {@code ggml-org/Laya-GGUF} revision
 * {@code 845f314dd047fc2444a5289a4d50c1e1d59e27e1} with {@code npm run catalog:triage}, not chosen
 * to make a test pass. A fixture invented for this architecture would hold constant exactly the
 * things that make it different — one feed-forward width, one rotary base, a uniform attention
 * pattern — and could not fail on any of them.
 */
@Tag("unit")
class ModernBertConfigTest {

  @Test
  void loadsTheLayaGgufContract() {
    ModernBertConfig config = ModernBertConfig.fromMetadata(layaMetadata());

    assertThat(config.embeddingDim()).isEqualTo(1024);
    assertThat(config.numLayers()).isEqualTo(30);
    assertThat(config.numHeads()).isEqualTo(16);
    assertThat(config.headDim()).isEqualTo(64);
    assertThat(config.vocabSize()).isEqualTo(50_368);
    assertThat(config.contextLength()).isEqualTo(8_192);
    assertThat(config.layerNormEps()).isEqualTo(1.0e-5f);
    assertThat(config.ropeTheta()).isEqualTo(160_000.0f);
    assertThat(config.ropeThetaSlidingWindow()).isEqualTo(10_000.0f);
    assertThat(config.slidingWindow()).isEqualTo(128);
    assertThat(config.slidingWindowPattern()).isEqualTo(3);
    assertThat(config.pooling()).isEqualTo(BertConfig.Pooling.MEAN);
  }

  @Test
  void separatesTheEncoderBlocksFromTheDecisionBlocks() {
    ModernBertConfig config = ModernBertConfig.fromMetadata(layaMetadata());

    assertThat(config.encoderLayers()).isEqualTo(28);
    assertThat(config.isDecisionLayer(27)).isFalse();
    assertThat(config.isDecisionLayer(28)).isTrue();
    assertThat(config.isDecisionLayer(29)).isTrue();
    // The widths are the whole reason this is a per-layer array: the encoder blocks gate a 2624
    // intermediate and the decision blocks run a plain 4096 one.
    assertThat(config.feedForwardLengthFor(0)).isEqualTo(2_624);
    assertThat(config.feedForwardLengthFor(27)).isEqualTo(2_624);
    assertThat(config.feedForwardLengthFor(28)).isEqualTo(4_096);
    assertThat(config.feedForwardLengthFor(29)).isEqualTo(4_096);
  }

  @Test
  void alternatesWindowedAndGlobalLayersAndGivesEachItsOwnRotaryBase() {
    ModernBertConfig config = ModernBertConfig.fromMetadata(layaMetadata());

    assertThat(config.isGlobalAttentionLayer(0)).isTrue();
    assertThat(config.isGlobalAttentionLayer(1)).isFalse();
    assertThat(config.isGlobalAttentionLayer(2)).isFalse();
    assertThat(config.isGlobalAttentionLayer(3)).isTrue();
    assertThat(config.ropeThetaFor(0)).isEqualTo(160_000.0f);
    assertThat(config.ropeThetaFor(1)).isEqualTo(10_000.0f);
    assertThat(config.ropeThetaFor(3)).isEqualTo(160_000.0f);
    // A decision block reads every option marker at once, so it is never windowed.
    assertThat(config.isGlobalAttentionLayer(28)).isTrue();
    assertThat(config.ropeThetaFor(28)).isEqualTo(160_000.0f);
  }

  @Test
  void readsTheDecisionHeadAndTranslatesTheBucketSpellings() {
    ModernBertConfig.Decision decision =
        ModernBertConfig.fromMetadata(layaMetadata()).decision().orElseThrow();

    assertThat(decision.type()).isEqualTo("laya");
    assertThat(decision.blockCount()).isEqualTo(2);
    assertThat(decision.maxHeadTokens()).isEqualTo(192);
    // The header spells these `choice.3_5` and `choice.11`; the reference spells them
    // `choice:3-5` and `choice:11+`, and a lookup with the header's spelling finds nothing.
    assertThat(decision.temperatures())
        .containsEntry("choice:2", 1.9063563f)
        .containsEntry("choice:3-5", 1.7601519f)
        .containsEntry("choice:6-10", 1.0000159f)
        .containsEntry("choice:11+", 0.100582808f)
        .containsEntry("score:3-5", 1.2514300f)
        .containsEntry("noul:2", 1.9833995f);
  }

  @Test
  void clampsTheTemperatureTheReferenceRefusesAndSaysSoBucketByBucket() {
    ModernBertConfig.Decision decision =
        ModernBertConfig.fromMetadata(layaMetadata()).decision().orElseThrow();

    // 0.100583 sharpens the logits about tenfold. The reference clamps it to 0.5 and warns that
    // the affected entries are uncalibrated; anything else publishes a coin flip as a certainty.
    assertThat(decision.temperatureFor("choice:11+")).contains(0.5f);
    assertThat(decision.isUncalibrated("choice:11+")).isTrue();

    assertThat(decision.temperatureFor("choice:3-5")).contains(1.7601519f);
    assertThat(decision.isUncalibrated("choice:3-5")).isFalse();
    assertThat(decision.isUncalibrated("noul:2")).isFalse();
  }

  @Test
  void buildsTheBucketKeyTheReferenceLooksUp() {
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.NOUL, 2))
        .isEqualTo("noul:2");
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.CHOICE, 1))
        .isEqualTo("choice:2");
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.CHOICE, 4))
        .isEqualTo("choice:3-5");
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.CHOICE, 8))
        .isEqualTo("choice:6-10");
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.CHOICE, 12))
        .isEqualTo("choice:11+");
    assertThat(ModernBertConfig.temperatureBucket(ModernBertConfig.QuestionType.SCORE, 5))
        .isEqualTo("score:3-5");
  }

  @Test
  void mapsEachQuestionTypeToItsRowOfTheTypeEmbedding() {
    // The reference's QTYPES: choice 0, score 1, noul 2. `token_types.weight` has exactly three
    // rows, and reading the wrong one conditions the head on the wrong question kind.
    assertThat(ModernBertConfig.QuestionType.CHOICE.row()).isZero();
    assertThat(ModernBertConfig.QuestionType.SCORE.row()).isEqualTo(1);
    assertThat(ModernBertConfig.QuestionType.NOUL.row()).isEqualTo(2);
  }

  @Test
  void refusesAMissingWindowPatternInsteadOfMakingEveryLayerWindowed() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.remove("modern-bert.attention.sliding_window_pattern");

    assertThatThrownBy(() -> ModernBertConfig.fromMetadata(new GgufMetadata(entries)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sliding_window_pattern");
  }

  @Test
  void refusesAMissingSlidingWindowRotaryBaseInsteadOfReusingTheGlobalOne() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.remove("modern-bert.rope.freq_base_swa");

    assertThatThrownBy(() -> ModernBertConfig.fromMetadata(new GgufMetadata(entries)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("freq_base_swa");
  }

  @Test
  void refusesAFeedForwardArrayThatDoesNotCoverEveryLayer() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.put(
        "modern-bert.feed_forward_length",
        new GgufMetadataValue.ArrayValue(
            GgufValueType.UINT32, List.of(new GgufMetadataValue.Uint32Value(2_624))));

    assertThatThrownBy(() -> ModernBertConfig.fromMetadata(new GgufMetadata(entries)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one width per layer");
  }

  @Test
  void refusesCausalAttentionInsteadOfSilentlyChangingTheModel() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.put("modern-bert.attention.causal", new GgufMetadataValue.BoolValue(true));

    assertThatThrownBy(() -> ModernBertConfig.fromMetadata(new GgufMetadata(entries)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bidirectional");
  }

  @Test
  void refusesADecisionHeadFamilyItDoesNotImplement() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.put("modern-bert.decision.type", new GgufMetadataValue.StringValue("something-else"));

    assertThatThrownBy(() -> ModernBertConfig.fromMetadata(new GgufMetadata(entries)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only laya is implemented");
  }

  @Test
  void loadsAnEncoderWithNoDecisionHeadAtAll() {
    Map<String, GgufMetadataValue> entries = new HashMap<>(layaMetadata().entries());
    entries.keySet().removeIf(key -> key.startsWith("modern-bert.decision."));

    ModernBertConfig config = ModernBertConfig.fromMetadata(new GgufMetadata(entries));

    assertThat(config.decision()).isEmpty();
    assertThat(config.encoderLayers()).isEqualTo(30);
    assertThat(config.isDecisionLayer(29)).isFalse();
  }

  /** The real header of {@code ggml-org/Laya-GGUF}, read with the catalog's triage tool. */
  private static GgufMetadata layaMetadata() {
    Map<String, GgufMetadataValue> entries = new HashMap<>();
    entries.put("general.architecture", new GgufMetadataValue.StringValue("modern-bert"));
    entries.put("modern-bert.embedding_length", new GgufMetadataValue.Uint32Value(1_024));
    entries.put("modern-bert.block_count", new GgufMetadataValue.Uint32Value(30));
    entries.put("modern-bert.attention.head_count", new GgufMetadataValue.Uint32Value(16));
    entries.put("modern-bert.context_length", new GgufMetadataValue.Uint32Value(8_192));
    entries.put("modern-bert.attention.causal", new GgufMetadataValue.BoolValue(false));
    entries.put(
        "modern-bert.attention.layer_norm_epsilon", new GgufMetadataValue.Float32Value(1.0e-5f));
    entries.put("modern-bert.rope.freq_base", new GgufMetadataValue.Float32Value(160_000.0f));
    entries.put("modern-bert.rope.freq_base_swa", new GgufMetadataValue.Float32Value(10_000.0f));
    entries.put("modern-bert.attention.sliding_window", new GgufMetadataValue.Uint32Value(128));
    entries.put(
        "modern-bert.attention.sliding_window_pattern", new GgufMetadataValue.Uint32Value(3));
    entries.put("modern-bert.classifier.pooling_type", new GgufMetadataValue.Uint32Value(1));
    entries.put("modern-bert.decision.type", new GgufMetadataValue.StringValue("laya"));
    entries.put("modern-bert.decision.block_count", new GgufMetadataValue.Uint32Value(2));
    entries.put("modern-bert.decision.max_head_tokens", new GgufMetadataValue.Uint32Value(192));
    entries.put(
        "modern-bert.decision.temperature.choice", new GgufMetadataValue.Float32Value(1.6369030f));
    entries.put(
        "modern-bert.decision.temperature.choice.2",
        new GgufMetadataValue.Float32Value(1.9063563f));
    entries.put(
        "modern-bert.decision.temperature.choice.3_5",
        new GgufMetadataValue.Float32Value(1.7601519f));
    entries.put(
        "modern-bert.decision.temperature.choice.6_10",
        new GgufMetadataValue.Float32Value(1.0000159f));
    entries.put(
        "modern-bert.decision.temperature.choice.11",
        new GgufMetadataValue.Float32Value(0.100582808f));
    entries.put(
        "modern-bert.decision.temperature.score", new GgufMetadataValue.Float32Value(1.2514300f));
    entries.put(
        "modern-bert.decision.temperature.score.3_5",
        new GgufMetadataValue.Float32Value(1.2514300f));
    entries.put(
        "modern-bert.decision.temperature.noul", new GgufMetadataValue.Float32Value(1.9833995f));
    entries.put(
        "modern-bert.decision.temperature.noul.2", new GgufMetadataValue.Float32Value(1.9833995f));

    // 2624 for the 28 encoder blocks, 4096 for the two decision blocks, exactly as the artifact
    // declares it.
    List<GgufMetadataValue> widths = new ArrayList<>(30);
    for (int layer = 0; layer < 28; layer++) {
      widths.add(new GgufMetadataValue.Uint32Value(2_624));
    }
    widths.add(new GgufMetadataValue.Uint32Value(4_096));
    widths.add(new GgufMetadataValue.Uint32Value(4_096));
    entries.put(
        "modern-bert.feed_forward_length",
        new GgufMetadataValue.ArrayValue(GgufValueType.UINT32, widths));

    List<GgufMetadataValue> tokens = new ArrayList<>(50_368);
    for (int token = 0; token < 50_368; token++) {
      tokens.add(new GgufMetadataValue.StringValue("token-" + token));
    }
    entries.put(
        "tokenizer.ggml.tokens", new GgufMetadataValue.ArrayValue(GgufValueType.STRING, tokens));
    return new GgufMetadata(entries);
  }
}

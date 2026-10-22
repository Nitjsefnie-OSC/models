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

import com.integrallis.models.backend.purejava.gguf.GgufMetadata;
import com.integrallis.models.backend.purejava.gguf.GgufMetadataValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Structural contract for a {@code modern-bert} GGUF encoder, including the typed-decision head.
 *
 * <p>Deliberately a sibling of {@link BertConfig} rather than another {@code EncoderStyle} on it.
 * Two of that record's invariants do not hold here: the feed-forward width is <em>per layer</em>
 * (2624 for the encoder blocks and 4096 for the decision blocks, carried as an array), and there
 * are <em>two</em> rotary bases rather than one — a global base for the full-attention layers and a
 * separate base for the sliding-window layers. Folding either into a single field is how a decoder
 * ends up rotating the right angles onto the wrong element pairs, which degrades output without
 * failing any shape assertion.
 *
 * <p>Nothing here is defaulted. A key this record needs and the artifact does not declare is an
 * error, because the alternative is a plausible-looking number nobody measured: {@code
 * sliding_window_pattern} silently defaulting to 1 would make every layer windowed and still load.
 */
public record ModernBertConfig(
    String architecture,
    int embeddingDim,
    int numLayers,
    int numHeads,
    int vocabSize,
    int contextLength,
    List<Integer> feedForwardLengths,
    float layerNormEps,
    float ropeTheta,
    float ropeThetaSlidingWindow,
    int slidingWindow,
    int slidingWindowPattern,
    BertConfig.Pooling pooling,
    Optional<Decision> decision) {

  /**
   * The typed-decision head a {@code decision.type} artifact carries on top of the encoder.
   *
   * @param type the head family the artifact declares; only {@code laya} is understood
   * @param blockCount transformer blocks appended after the encoder, which carry biases and a plain
   *     feed-forward where the encoder blocks carry neither
   * @param maxHeadTokens the question-and-options budget, shared by all options
   * @param temperatures calibration temperature per {@code type:option-count} bucket, as declared
   * @param clampedTemperatures the same map with each value confined to {@link #TEMPERATURE_MIN}
   *     ..{@link #TEMPERATURE_MAX}; see {@link #clampTemperature(float)}
   */
  public record Decision(
      String type,
      int blockCount,
      int maxHeadTokens,
      Map<String, Float> temperatures,
      Map<String, Float> clampedTemperatures) {

    public Decision {
      Objects.requireNonNull(type, "type");
      if (!"laya".equals(type)) {
        throw new IllegalArgumentException(
            "unsupported modern-bert decision head type: " + type + "; only laya is implemented");
      }
      if (blockCount <= 0) {
        throw new IllegalArgumentException("decision.block_count must be > 0: " + blockCount);
      }
      if (maxHeadTokens <= 0) {
        throw new IllegalArgumentException(
            "decision.max_head_tokens must be > 0: " + maxHeadTokens);
      }
      temperatures = Map.copyOf(temperatures);
      clampedTemperatures = Map.copyOf(clampedTemperatures);
    }

    /**
     * Whether a bucket's declared temperature had to be clamped, which makes it uncalibrated.
     *
     * <p>A caller that publishes a probability for such a bucket is publishing a number the model's
     * own reference implementation refuses to apply.
     */
    public boolean isUncalibrated(String bucket) {
      Float declared = temperatures.get(bucket);
      Float applied = clampedTemperatures.get(bucket);
      return declared != null && applied != null && Math.abs(declared - applied) > 1.0e-9f;
    }

    /** The temperature to apply for a bucket, or the empty optional when none is declared. */
    public Optional<Float> temperatureFor(String bucket) {
      return Optional.ofNullable(clampedTemperatures.get(bucket));
    }
  }

  /**
   * Lowest temperature worth applying, matching the reference implementation.
   *
   * <p>A temperature below one sharpens the logits rather than softening them. The checkpoint
   * behind {@code ggml-org/Laya-GGUF} declares 0.100583 for {@code choice:11+}, which multiplies
   * the logits about tenfold; the reference refuses it and says why — a 0.24 top probability would
   * be published as 0.99, so a caller gating on confidence is told a coin flip is a certainty.
   */
  public static final float TEMPERATURE_MIN = 0.5f;

  /** Highest temperature worth applying, matching the reference implementation. */
  public static final float TEMPERATURE_MAX = 5.0f;

  /** Question types the head understands, in the order its type embedding rows are stored. */
  public enum QuestionType {
    CHOICE,
    SCORE,
    NOUL;

    /** The row of {@code token_types.weight} this type uses. */
    public int row() {
      return ordinal();
    }

    /** The name the temperature bucket keys use. */
    public String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public ModernBertConfig {
    Objects.requireNonNull(architecture, "architecture");
    requirePositive(embeddingDim, "embeddingDim");
    requirePositive(numLayers, "numLayers");
    requirePositive(numHeads, "numHeads");
    requirePositive(vocabSize, "vocabSize");
    requirePositive(contextLength, "contextLength");
    requirePositive(slidingWindow, "slidingWindow");
    requirePositive(slidingWindowPattern, "slidingWindowPattern");
    Objects.requireNonNull(pooling, "pooling");
    Objects.requireNonNull(decision, "decision");
    feedForwardLengths = List.copyOf(feedForwardLengths);
    if (feedForwardLengths.size() != numLayers) {
      throw new IllegalArgumentException(
          "feed_forward_length must carry one width per layer: got "
              + feedForwardLengths.size()
              + " for "
              + numLayers
              + " layers");
    }
    for (int layer = 0; layer < feedForwardLengths.size(); layer++) {
      requirePositive(feedForwardLengths.get(layer), "feedForwardLengths[" + layer + "]");
    }
    if (embeddingDim % numHeads != 0) {
      throw new IllegalArgumentException(
          "embeddingDim must be divisible by numHeads: " + embeddingDim + " % " + numHeads);
    }
    requireFinitePositive(layerNormEps, "layerNormEps");
    requireFinitePositive(ropeTheta, "ropeTheta");
    requireFinitePositive(ropeThetaSlidingWindow, "ropeThetaSlidingWindow");
    if (decision.isPresent() && decision.get().blockCount() >= numLayers) {
      throw new IllegalArgumentException(
          "decision.block_count "
              + decision.get().blockCount()
              + " must leave encoder blocks behind out of "
              + numLayers);
    }
  }

  /** Width of one attention head. */
  public int headDim() {
    return embeddingDim / numHeads;
  }

  /** Blocks that run the bidirectional encoder, before any decision block. */
  public int encoderLayers() {
    return numLayers - decision.map(Decision::blockCount).orElse(0);
  }

  /**
   * Whether layer {@code index} attends over the whole sequence rather than a sliding window.
   *
   * <p>ModernBERT alternates: every {@code slidingWindowPattern}-th layer is global and the rest
   * are windowed, so with a pattern of 3 layers 0, 3, 6 … see everything. The two layer kinds use
   * <em>different</em> rotary bases, which is why this predicate and {@link #ropeThetaFor(int)} are
   * the same decision expressed twice and must never disagree.
   */
  public boolean isGlobalAttentionLayer(int index) {
    if (index < 0 || index >= numLayers) {
      throw new IndexOutOfBoundsException("layer " + index + " of " + numLayers);
    }
    if (index >= encoderLayers()) {
      // Decision blocks are not part of the windowed pattern: they attend over the whole
      // sequence, because their job is to read every option marker at once.
      return true;
    }
    return index % slidingWindowPattern == 0;
  }

  /** The rotary base layer {@code index} uses: the global one, or the sliding-window one. */
  public float ropeThetaFor(int index) {
    return isGlobalAttentionLayer(index) ? ropeTheta : ropeThetaSlidingWindow;
  }

  /** The feed-forward width of layer {@code index}; encoder blocks gate, decision blocks do not. */
  public int feedForwardLengthFor(int index) {
    return feedForwardLengths.get(index);
  }

  /** Whether layer {@code index} is a decision block, which carries biases and a plain MLP. */
  public boolean isDecisionLayer(int index) {
    return index >= encoderLayers();
  }

  /**
   * The temperature bucket key for a question type and an option count.
   *
   * <p>Spelled the way the reference spells it — {@code choice:3-5}, {@code choice:11+} — and not
   * the way the GGUF metadata spells it ({@code temperature.choice.3_5}, {@code
   * temperature.choice.11}). Building this key by hand from the metadata spelling is a mistake
   * already made once while writing the oracle for this model: every bucket missed, and the generic
   * per-type temperature was applied to all four choice arities without anything failing.
   */
  public static String temperatureBucket(QuestionType type, int options) {
    if (options <= 0) {
      throw new IllegalArgumentException("a question needs at least one option: " + options);
    }
    String size = options <= 2 ? "2" : options <= 5 ? "3-5" : options <= 10 ? "6-10" : "11+";
    return type.key() + ":" + size;
  }

  /** A temperature confined to the usable range, with a non-finite value falling back to 1. */
  public static float clampTemperature(float temperature) {
    if (!Float.isFinite(temperature)) {
      return 1.0f;
    }
    return Math.min(TEMPERATURE_MAX, Math.max(TEMPERATURE_MIN, temperature));
  }

  /** Reads and validates the {@code modern-bert} GGUF metadata contract. */
  public static ModernBertConfig fromMetadata(GgufMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    String architecture = metadata.getString("general.architecture").orElse("");
    if (!"modern-bert".equals(architecture)) {
      throw new IllegalArgumentException(
          "ModernBERT configuration requires architecture modern-bert: " + architecture);
    }
    String prefix = architecture + ".";
    if (metadata.getBool(prefix + "attention.causal").orElse(false)) {
      throw new IllegalArgumentException(
          "ModernBERT attention must be bidirectional, but the artifact declares causal attention");
    }
    int numLayers = requiredUint32(metadata, prefix + "block_count");
    return new ModernBertConfig(
        architecture,
        requiredUint32(metadata, prefix + "embedding_length"),
        numLayers,
        requiredUint32(metadata, prefix + "attention.head_count"),
        metadata
            .getArraySize("tokenizer.ggml.tokens")
            .orElseThrow(
                () -> new IllegalArgumentException("missing tokenizer.ggml.tokens vocabulary")),
        requiredUint32(metadata, prefix + "context_length"),
        feedForwardLengths(metadata, prefix + "feed_forward_length", numLayers),
        requiredFloat32(metadata, prefix + "attention.layer_norm_epsilon"),
        requiredFloat32(metadata, prefix + "rope.freq_base"),
        requiredFloat32(metadata, prefix + "rope.freq_base_swa"),
        requiredUint32(metadata, prefix + "attention.sliding_window"),
        requiredUint32(metadata, prefix + "attention.sliding_window_pattern"),
        BertConfig.Pooling.fromCode(requiredUint32(metadata, prefix + "classifier.pooling_type")),
        decision(metadata, prefix));
  }

  private static Optional<Decision> decision(GgufMetadata metadata, String prefix) {
    Optional<String> type = metadata.getString(prefix + "decision.type");
    if (type.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Float> declared = new LinkedHashMap<>();
    Map<String, Float> clamped = new LinkedHashMap<>();
    String temperaturePrefix = prefix + "decision.temperature.";
    for (Map.Entry<String, GgufMetadataValue> entry : metadata.entries().entrySet()) {
      if (!entry.getKey().startsWith(temperaturePrefix)) {
        continue;
      }
      if (!(entry.getValue() instanceof GgufMetadataValue.Float32Value value)) {
        throw new IllegalArgumentException(
            "decision temperature must be float32: " + entry.getKey());
      }
      // `choice.3_5` in the header is `choice:3-5` to the reference, and `choice.11` is
      // `choice:11+`. Translate here, once, rather than at every lookup.
      String suffix = entry.getKey().substring(temperaturePrefix.length());
      declared.put(bucketKeyOf(suffix), value.value());
      clamped.put(bucketKeyOf(suffix), clampTemperature(value.value()));
    }
    return Optional.of(
        new Decision(
            type.get(),
            requiredUint32(metadata, prefix + "decision.block_count"),
            requiredUint32(metadata, prefix + "decision.max_head_tokens"),
            declared,
            clamped));
  }

  private static String bucketKeyOf(String metadataSuffix) {
    int dot = metadataSuffix.indexOf('.');
    if (dot < 0) {
      // A per-type default with no arity, e.g. `temperature.choice`.
      return metadataSuffix;
    }
    String type = metadataSuffix.substring(0, dot);
    String size = metadataSuffix.substring(dot + 1);
    return type
        + ":"
        + switch (size) {
          case "2" -> "2";
          case "3_5" -> "3-5";
          case "6_10" -> "6-10";
          case "11" -> "11+";
          default ->
              throw new IllegalArgumentException(
                  "unrecognised temperature bucket in metadata: " + metadataSuffix);
        };
  }

  private static List<Integer> feedForwardLengths(
      GgufMetadata metadata, String key, int numLayers) {
    Optional<GgufMetadataValue> raw = Optional.ofNullable(metadata.entries().get(key));
    if (raw.isEmpty()) {
      throw new IllegalArgumentException("missing " + key);
    }
    if (raw.get() instanceof GgufMetadataValue.ArrayValue array) {
      List<Integer> widths = new ArrayList<>(array.elements().size());
      for (GgufMetadataValue element : array.elements()) {
        widths.add(intOf(element, key));
      }
      return widths;
    }
    // A uniform artifact may declare one width for every layer; repeating it here keeps the
    // per-layer accessor the only thing the forward pass has to know about.
    int width = intOf(raw.get(), key);
    List<Integer> widths = new ArrayList<>(numLayers);
    for (int layer = 0; layer < numLayers; layer++) {
      widths.add(width);
    }
    return widths;
  }

  private static int intOf(GgufMetadataValue value, String key) {
    return switch (value) {
      case GgufMetadataValue.Uint32Value v -> v.value();
      case GgufMetadataValue.Int32Value v -> v.value();
      case GgufMetadataValue.Uint64Value v -> Math.toIntExact(v.value());
      case GgufMetadataValue.Int64Value v -> Math.toIntExact(v.value());
      default ->
          throw new IllegalArgumentException(
              key + " must hold integers, got " + value.getClass().getSimpleName());
    };
  }

  private static int requiredUint32(GgufMetadata metadata, String key) {
    return metadata
        .getUint32(key)
        .orElseThrow(() -> new IllegalArgumentException("missing " + key));
  }

  private static float requiredFloat32(GgufMetadata metadata, String key) {
    return metadata
        .getFloat32(key)
        .orElseThrow(() -> new IllegalArgumentException("missing " + key));
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be > 0: " + value);
    }
  }

  private static void requireFinitePositive(float value, String name) {
    if (!(value > 0.0f) || !Float.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite and > 0: " + value);
    }
  }
}

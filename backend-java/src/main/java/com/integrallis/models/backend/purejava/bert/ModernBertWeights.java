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

import com.integrallis.models.backend.purejava.gguf.GgufFile;
import com.integrallis.models.backend.purejava.gguf.GgufTensorData;
import com.integrallis.models.backend.purejava.gguf.GgufTensorValues;
import com.integrallis.models.backend.purejava.ops.TensorOps;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mapped {@code modern-bert} tensors, including the typed-decision head.
 *
 * <p>The tensor contract is not uniform across blocks, and the loader refuses to paper over that:
 *
 * <ul>
 *   <li>block 0 has no {@code attn_norm}. ModernBERT normalises the embeddings once ({@code
 *       token_embd_norm}) and the first block's attention reads that, so demanding an {@code
 *       attn_norm} for every block would fail to load the real artifact.
 *   <li>the decision blocks carry biases on every projection and norm, and a <em>plain</em>
 *       feed-forward. The encoder blocks carry no bias at all and pack a gate: their {@code ffn_up}
 *       is twice the intermediate width, which is the only place the GeGLU is visible in the file.
 *   <li>{@code token_embd_norm} has a weight and <em>no bias</em>, unlike classic BERT's.
 * </ul>
 *
 * <p>{@link #unclaimedTensors(GgufFile)} exists because "every shape matched" is a weaker statement
 * than it sounds: a loader can satisfy every assertion while quietly ignoring a tensor the model
 * needs. That has to be checkable, so it is.
 */
final class ModernBertWeights {

  /** A row-major matrix in GGUF's [columns, rows] shape convention. */
  record Matrix(
      java.lang.foreign.MemorySegment data,
      com.integrallis.models.backend.purejava.gguf.GgufTensorType type,
      int rows,
      int columns) {
    Matrix {
      Objects.requireNonNull(data, "data");
      Objects.requireNonNull(type, "type");
      if (rows <= 0 || columns <= 0) {
        throw new IllegalArgumentException("matrix dimensions must be positive");
      }
    }
  }

  /**
   * One block, encoder or decision.
   *
   * <p>{@code attentionNormWeight} is null for block 0 only. The bias fields are null for every
   * encoder block and present for every decision block; {@code gated} says which kind this is, so a
   * forward pass branches on the config rather than on whether a pointer happens to be set.
   */
  record Layer(
      float[] attentionNormWeight,
      float[] attentionNormBias,
      Matrix queryKeyValue,
      float[] queryKeyValueBias,
      Matrix attentionOutput,
      float[] attentionOutputBias,
      float[] feedForwardNormWeight,
      float[] feedForwardNormBias,
      Matrix feedForwardUp,
      float[] feedForwardUpBias,
      Matrix feedForwardDown,
      float[] feedForwardDownBias,
      boolean gated,
      int intermediateWidth) {}

  /** The option-marker scorer: {@code LayerNorm -> Linear(d,d) -> GELU -> Linear(d,1)}. */
  record Scorer(
      float[] normWeight,
      float[] normBias,
      Matrix dense,
      float[] denseBias,
      float[] output,
      float outputBias) {}

  private final Matrix tokenEmbeddings;
  private final float[] tokenEmbeddingNorm;
  private final Matrix questionTypeEmbeddings;
  private final float[] outputNorm;
  private final Layer[] layers;
  private final Optional<Scorer> scorer;

  private ModernBertWeights(
      Matrix tokenEmbeddings,
      float[] tokenEmbeddingNorm,
      Matrix questionTypeEmbeddings,
      float[] outputNorm,
      Layer[] layers,
      Optional<Scorer> scorer) {
    this.tokenEmbeddings = tokenEmbeddings;
    this.tokenEmbeddingNorm = tokenEmbeddingNorm;
    this.questionTypeEmbeddings = questionTypeEmbeddings;
    this.outputNorm = outputNorm;
    this.layers = layers;
    this.scorer = scorer;
  }

  /** Loads and validates the {@code modern-bert} tensor contract. */
  static ModernBertWeights load(GgufFile file, ModernBertConfig config) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(config, "config");
    int dim = config.embeddingDim();

    Matrix tokenEmbeddings = table(file, "token_embd.weight", config.vocabSize(), dim);
    float[] tokenEmbeddingNorm = vector(file, "token_embd_norm.weight", dim);
    Matrix questionTypes = table(file, "token_types.weight", 3, dim);
    float[] outputNorm = vector(file, "output_norm.weight", dim);

    Layer[] layers = new Layer[config.numLayers()];
    for (int index = 0; index < layers.length; index++) {
      String prefix = "blk." + index + ".";
      boolean decision = config.isDecisionLayer(index);
      int declared = config.feedForwardLengthFor(index);
      // The encoder packs gate and value into one tensor, so the file's width is twice the
      // intermediate the header declares. The decision blocks do not gate, and theirs matches.
      int upWidth = decision ? declared : 2 * declared;
      layers[index] =
          new Layer(
              index == 0 ? null : vector(file, prefix + "attn_norm.weight", dim),
              decision ? vector(file, prefix + "attn_norm.bias", dim) : null,
              projection(file, prefix + "attn_qkv.weight", 3 * dim, dim),
              decision ? vector(file, prefix + "attn_qkv.bias", 3 * dim) : null,
              projection(file, prefix + "attn_output.weight", dim, dim),
              decision ? vector(file, prefix + "attn_output.bias", dim) : null,
              vector(file, prefix + "ffn_norm.weight", dim),
              decision ? vector(file, prefix + "ffn_norm.bias", dim) : null,
              projection(file, prefix + "ffn_up.weight", upWidth, dim),
              decision ? vector(file, prefix + "ffn_up.bias", upWidth) : null,
              projection(file, prefix + "ffn_down.weight", dim, declared),
              decision ? vector(file, prefix + "ffn_down.bias", dim) : null,
              !decision,
              declared);
    }

    Optional<Scorer> scorer =
        config.decision().isEmpty()
            ? Optional.empty()
            : Optional.of(
                new Scorer(
                    vector(file, "cls.norm.weight", dim),
                    vector(file, "cls.norm.bias", dim),
                    projection(file, "cls.weight", dim, dim),
                    vector(file, "cls.bias", dim),
                    vector(file, "cls.output.weight", dim),
                    vector(file, "cls.output.bias", 1)[0]));

    return new ModernBertWeights(
        tokenEmbeddings, tokenEmbeddingNorm, questionTypes, outputNorm, layers, scorer);
  }

  /**
   * Tensors present in the file that this loader does not read.
   *
   * <p>Empty is the only acceptable answer for an artifact we claim to support. A non-empty answer
   * means the file carries weights the forward pass will not apply, which is a silent wrong answer
   * rather than a failure.
   */
  static Set<String> unclaimedTensors(GgufFile file) {
    Objects.requireNonNull(file, "file");
    ModernBertConfig config = ModernBertConfig.fromMetadata(file.metadata());
    Set<String> claimed = new LinkedHashSet<>();
    claimed.add("token_embd.weight");
    claimed.add("token_embd_norm.weight");
    claimed.add("token_types.weight");
    claimed.add("output_norm.weight");
    if (config.decision().isPresent()) {
      claimed.add("cls.norm.weight");
      claimed.add("cls.norm.bias");
      claimed.add("cls.weight");
      claimed.add("cls.bias");
      claimed.add("cls.output.weight");
      claimed.add("cls.output.bias");
    }
    for (int index = 0; index < config.numLayers(); index++) {
      String prefix = "blk." + index + ".";
      if (index > 0) {
        claimed.add(prefix + "attn_norm.weight");
      }
      claimed.add(prefix + "attn_qkv.weight");
      claimed.add(prefix + "attn_output.weight");
      claimed.add(prefix + "ffn_norm.weight");
      claimed.add(prefix + "ffn_up.weight");
      claimed.add(prefix + "ffn_down.weight");
      if (config.isDecisionLayer(index)) {
        claimed.add(prefix + "attn_norm.bias");
        claimed.add(prefix + "attn_qkv.bias");
        claimed.add(prefix + "attn_output.bias");
        claimed.add(prefix + "ffn_norm.bias");
        claimed.add(prefix + "ffn_up.bias");
        claimed.add(prefix + "ffn_down.bias");
      }
    }
    Set<String> unclaimed = new LinkedHashSet<>();
    for (com.integrallis.models.backend.purejava.gguf.GgufTensorInfo info : file.tensorInfos()) {
      if (!claimed.contains(info.name())) {
        unclaimed.add(info.name());
      }
    }
    return unclaimed;
  }

  Layer layer(int index) {
    return layers[index];
  }

  int layerCount() {
    return layers.length;
  }

  float[] tokenEmbeddingNorm() {
    return tokenEmbeddingNorm;
  }

  float[] outputNorm() {
    return outputNorm;
  }

  Optional<Scorer> scorer() {
    return scorer;
  }

  void tokenEmbedding(int token, float[] output) {
    row(tokenEmbeddings, token, output);
  }

  /** The type embedding added at every position, one row per {@code QuestionType}. */
  void questionTypeEmbedding(int type, float[] output) {
    row(questionTypeEmbeddings, type, output);
  }

  private static void row(Matrix table, int index, float[] output) {
    if (index < 0 || index >= table.rows()) {
      throw new IllegalArgumentException("embedding row out of range: " + index);
    }
    if (output.length != table.columns()) {
      throw new IllegalArgumentException(
          "output width must be " + table.columns() + ": " + output.length);
    }
    GgufTensorValues.dequantizeRow(table.data(), table.type(), index, table.columns(), output);
  }

  private static Matrix table(GgufFile file, String name, int rows, int columns) {
    return matrix(file, name, rows, columns, false);
  }

  private static Matrix projection(GgufFile file, String name, int rows, int columns) {
    return matrix(file, name, rows, columns, true);
  }

  private static Matrix matrix(
      GgufFile file, String name, int rows, int columns, boolean projection) {
    GgufTensorData tensor = file.getTensor(name);
    long[] expected = {columns, rows};
    if (!Arrays.equals(tensor.shape(), expected)) {
      throw new IllegalArgumentException(
          name
              + " shape must be "
              + Arrays.toString(expected)
              + ": "
              + Arrays.toString(tensor.shape()));
    }
    if (projection && !TensorOps.supportsBatchedMatmul(tensor.type())) {
      throw new IllegalArgumentException(
          name + " uses unsupported batched projection type " + tensor.type());
    }
    return new Matrix(tensor.dataSegment(), tensor.type(), rows, columns);
  }

  /**
   * A one-dimensional tensor, tolerating the trailing unit dimensions a converter may keep.
   *
   * <p>{@code Laya-Q8_0.gguf} declares {@code cls.output.weight} as {@code [1024]} and {@code
   * Laya-BF16.gguf}, from the same repository and the same conversion run, declares it as {@code
   * [1024, 1]}. Both hold 1024 elements in the same order, so refusing one of them would reject a
   * real artifact over a shape the converter chose. What is still checked is the element count:
   * only unit dimensions are dropped, so a genuinely wrong shape is still refused.
   */
  private static float[] vector(GgufFile file, String name, int size) {
    GgufTensorData tensor = file.getTensor(name);
    long elements = 1;
    for (long dimension : tensor.shape()) {
      elements *= dimension;
    }
    boolean onlyUnitsBeyondFirst = true;
    for (int axis = 1; axis < tensor.shape().length; axis++) {
      onlyUnitsBeyondFirst &= tensor.shape()[axis] == 1;
    }
    if (elements != size || !onlyUnitsBeyondFirst) {
      throw new IllegalArgumentException(
          name
              + " shape must be "
              + Arrays.toString(new long[] {size})
              + ", optionally with trailing unit dimensions: "
              + Arrays.toString(tensor.shape()));
    }
    return GgufTensorValues.toFloatArray(tensor);
  }
}

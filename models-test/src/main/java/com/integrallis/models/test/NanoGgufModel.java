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
package com.integrallis.models.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Writes a deterministic, tiny GGUF model for examples and downstream integration tests. */
public final class NanoGgufModel {
  private static final int GGUF_MAGIC = 0x46554747;
  private static final int GGUF_VERSION = 3;
  private static final int GGUF_ALIGNMENT = 32;
  private static final int GGUF_TYPE_UINT32 = 4;
  private static final int GGUF_TYPE_FLOAT32 = 6;
  private static final int GGUF_TYPE_STRING = 8;
  private static final int GGUF_TYPE_ARRAY = 9;
  private static final int GGUF_TENSOR_F32 = 0;
  private static final int GGUF_TENSOR_F16 = 1;

  private static final int DIMENSION = 16;
  private static final int ATTENTION_HEADS = 2;
  private static final int KV_HEADS = 1;
  private static final int HIDDEN_DIMENSION = 32;
  private static final int VOCABULARY_SIZE = 32;
  private static final int LAYERS = 2;
  private static final int CONTEXT_LENGTH = 64;

  private NanoGgufModel() {}

  /**
   * Writes the fixture to {@code target}, replacing an existing file.
   *
   * @return the supplied target path
   */
  public static Path write(Path target) throws IOException {
    Objects.requireNonNull(target, "target");
    Path parent = target.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(target, build());
    return target;
  }

  private static byte[] build() {
    List<String> tokens = new ArrayList<>(VOCABULARY_SIZE);
    List<Float> scores = new ArrayList<>(VOCABULARY_SIZE);
    for (int index = 0; index < VOCABULARY_SIZE; index++) {
      tokens.add("t" + index);
      scores.add(0.0f);
    }

    GgufBuilder builder =
        new GgufBuilder()
            .string("general.name", "ModelsNano")
            .string("general.architecture", "llama")
            .uint32("llama.embedding_length", DIMENSION)
            .uint32("llama.block_count", LAYERS)
            .uint32("llama.attention.head_count", ATTENTION_HEADS)
            .uint32("llama.attention.head_count_kv", KV_HEADS)
            .uint32("llama.vocab_size", VOCABULARY_SIZE)
            .uint32("llama.context_length", CONTEXT_LENGTH)
            .uint32("llama.feed_forward_length", HIDDEN_DIMENSION)
            .strings("tokenizer.ggml.tokens", tokens)
            .floats("tokenizer.ggml.scores", scores)
            .uint32("tokenizer.ggml.bos_token_id", 0)
            .uint32("tokenizer.ggml.eos_token_id", 1);

    Random random = new Random(42);
    builder.tensor(
        "token_embd.weight",
        new long[] {DIMENSION, VOCABULARY_SIZE},
        randomFloats(random, VOCABULARY_SIZE * DIMENSION));
    builder.tensor("output_norm.weight", new long[] {DIMENSION}, constantFloats(DIMENSION, 1.0f));
    builder.tensor(
        "output.weight",
        new long[] {DIMENSION, VOCABULARY_SIZE},
        randomFloats(random, VOCABULARY_SIZE * DIMENSION));

    int kvDimension = KV_HEADS * (DIMENSION / ATTENTION_HEADS);
    for (int layer = 0; layer < LAYERS; layer++) {
      String prefix = "blk." + layer + ".";
      builder.tensor(
          prefix + "attn_norm.weight", new long[] {DIMENSION}, constantFloats(DIMENSION, 1.0f));
      builder.tensor(
          prefix + "attn_q.weight",
          new long[] {DIMENSION, DIMENSION},
          randomFloats(random, DIMENSION * DIMENSION));
      builder.tensor(
          prefix + "attn_k.weight",
          new long[] {DIMENSION, kvDimension},
          randomFloats(random, kvDimension * DIMENSION));
      builder.tensor(
          prefix + "attn_v.weight",
          new long[] {DIMENSION, kvDimension},
          randomFloats(random, kvDimension * DIMENSION));
      builder.tensor(
          prefix + "attn_output.weight",
          new long[] {DIMENSION, DIMENSION},
          randomFloats(random, DIMENSION * DIMENSION));
      builder.tensor(
          prefix + "ffn_norm.weight", new long[] {DIMENSION}, constantFloats(DIMENSION, 1.0f));
      builder.tensor(
          prefix + "ffn_gate.weight",
          new long[] {DIMENSION, HIDDEN_DIMENSION},
          randomFloats(random, HIDDEN_DIMENSION * DIMENSION));
      builder.tensor(
          prefix + "ffn_up.weight",
          new long[] {DIMENSION, HIDDEN_DIMENSION},
          randomFloats(random, HIDDEN_DIMENSION * DIMENSION));
      builder.tensor(
          prefix + "ffn_down.weight",
          new long[] {HIDDEN_DIMENSION, DIMENSION},
          randomFloats(random, DIMENSION * HIDDEN_DIMENSION));
    }
    return builder.build();
  }

  /**
   * Writes a nano {@code modern-bert} typed-decision fixture, in the shape Laya actually has.
   *
   * <p>Deliberately heterogeneous, because the things that make this architecture different are
   * exactly the things a uniform fixture holds constant and therefore cannot fail on:
   *
   * <ul>
   *   <li>block 0 carries <strong>no</strong> {@code attn_norm}; blocks 1 and up do
   *   <li>the last {@code DECISION_BLOCKS} blocks carry <strong>biases</strong> on every projection
   *       and norm, and a plain feed-forward where the encoder blocks gate
   *   <li>two feed-forward widths: {@code 2 * MODERN_INTERMEDIATE} packed for the encoder's GeGLU,
   *       and {@code MODERN_DECISION_FFN} flat for the decision blocks
   *   <li>four encoder blocks against a window pattern of 3, so the global/windowed alternation is
   *       observable rather than degenerate
   * </ul>
   *
   * @return the supplied target path
   */
  public static Path writeModernBertDecision(Path target) throws IOException {
    Objects.requireNonNull(target, "target");
    Path parent = target.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(target, buildModernBertDecision());
    return target;
  }

  /** Embedding width of the modern-bert fixture. */
  public static final int MODERN_DIMENSION = 16;

  /** Attention heads of the modern-bert fixture. */
  public static final int MODERN_HEADS = 2;

  /** Encoder blocks of the modern-bert fixture. */
  public static final int MODERN_ENCODER_BLOCKS = 4;

  /** Decision blocks of the modern-bert fixture, matching Laya's two. */
  public static final int MODERN_DECISION_BLOCKS = 2;

  /** Gated intermediate width of an encoder block; {@code ffn_up} packs twice this. */
  public static final int MODERN_INTERMEDIATE = 8;

  /** Flat feed-forward width of a decision block. */
  public static final int MODERN_DECISION_FFN = 32;

  /** Vocabulary of the modern-bert fixture. */
  public static final int MODERN_VOCABULARY = 32;

  /** Context length of the modern-bert fixture. */
  public static final int MODERN_CONTEXT = 64;

  /** Sliding window of the modern-bert fixture. */
  public static final int MODERN_WINDOW = 4;

  /** Global-attention period of the modern-bert fixture. */
  public static final int MODERN_WINDOW_PATTERN = 3;

  /** Question types the decision head embeds, matching Laya's three. */
  public static final int MODERN_TOKEN_TYPES = 3;

  private static byte[] buildModernBertDecision() {
    int blocks = MODERN_ENCODER_BLOCKS + MODERN_DECISION_BLOCKS;
    List<String> tokens = new ArrayList<>(MODERN_VOCABULARY);
    for (int index = 0; index < MODERN_VOCABULARY; index++) {
      tokens.add("t" + index);
    }
    List<Integer> widths = new ArrayList<>(blocks);
    for (int block = 0; block < MODERN_ENCODER_BLOCKS; block++) {
      widths.add(MODERN_INTERMEDIATE);
    }
    for (int block = 0; block < MODERN_DECISION_BLOCKS; block++) {
      widths.add(MODERN_DECISION_FFN);
    }

    GgufBuilder builder =
        new GgufBuilder()
            .string("general.name", "ModelsNanoModernBert")
            .string("general.architecture", "modern-bert")
            .uint32("modern-bert.embedding_length", MODERN_DIMENSION)
            .uint32("modern-bert.block_count", blocks)
            .uint32("modern-bert.attention.head_count", MODERN_HEADS)
            .uint32("modern-bert.context_length", MODERN_CONTEXT)
            .uint32s("modern-bert.feed_forward_length", widths)
            .float32("modern-bert.attention.layer_norm_epsilon", 1.0e-5f)
            .float32("modern-bert.rope.freq_base", 160_000.0f)
            .float32("modern-bert.rope.freq_base_swa", 10_000.0f)
            .uint32("modern-bert.attention.sliding_window", MODERN_WINDOW)
            .uint32("modern-bert.attention.sliding_window_pattern", MODERN_WINDOW_PATTERN)
            .uint32("modern-bert.classifier.pooling_type", 1)
            .string("modern-bert.decision.type", "laya")
            .uint32("modern-bert.decision.block_count", MODERN_DECISION_BLOCKS)
            .uint32("modern-bert.decision.max_head_tokens", 24)
            .float32("modern-bert.decision.temperature.choice", 1.6369030f)
            .float32("modern-bert.decision.temperature.choice.2", 1.9063563f)
            .float32("modern-bert.decision.temperature.choice.3_5", 1.7601519f)
            .float32("modern-bert.decision.temperature.choice.6_10", 1.0000159f)
            .float32("modern-bert.decision.temperature.choice.11", 0.1005828f)
            .float32("modern-bert.decision.temperature.score", 1.2514300f)
            .float32("modern-bert.decision.temperature.score.3_5", 1.2514300f)
            .float32("modern-bert.decision.temperature.noul", 1.9833995f)
            .float32("modern-bert.decision.temperature.noul.2", 1.9833995f)
            .strings("tokenizer.ggml.tokens", tokens)
            .uint32("tokenizer.ggml.mask_token_id", 4)
            .uint32("tokenizer.ggml.bos_token_id", 1)
            .uint32("tokenizer.ggml.eos_token_id", 2);

    Random random = new Random(20261021);
    int dim = MODERN_DIMENSION;
    builder.halfTensor(
        "token_embd.weight",
        new long[] {dim, MODERN_VOCABULARY},
        randomHalves(random, MODERN_VOCABULARY * dim));
    // Weight only: this architecture's embedding norm carries no bias, unlike classic BERT's.
    builder.tensor("token_embd_norm.weight", new long[] {dim}, constantFloats(dim, 1.0f));
    builder.halfTensor(
        "token_types.weight",
        new long[] {dim, MODERN_TOKEN_TYPES},
        randomHalves(random, MODERN_TOKEN_TYPES * dim));
    builder.tensor("output_norm.weight", new long[] {dim}, constantFloats(dim, 1.0f));
    // The scorer: LayerNorm, then a square projection, then a single-logit projection.
    builder.tensor("cls.norm.weight", new long[] {dim}, constantFloats(dim, 1.0f));
    builder.tensor("cls.norm.bias", new long[] {dim}, constantFloats(dim, 0.0f));
    builder.halfTensor("cls.weight", new long[] {dim, dim}, randomHalves(random, dim * dim));
    builder.tensor("cls.bias", new long[] {dim}, constantFloats(dim, 0.0f));
    builder.tensor("cls.output.weight", new long[] {dim}, randomFloats(random, dim));
    builder.tensor("cls.output.bias", new long[] {1}, constantFloats(1, 0.0f));

    for (int block = 0; block < blocks; block++) {
      String prefix = "blk." + block + ".";
      boolean decision = block >= MODERN_ENCODER_BLOCKS;
      int upWidth = decision ? MODERN_DECISION_FFN : 2 * MODERN_INTERMEDIATE;
      int downWidth = decision ? MODERN_DECISION_FFN : MODERN_INTERMEDIATE;

      if (block > 0) {
        builder.tensor(prefix + "attn_norm.weight", new long[] {dim}, constantFloats(dim, 1.0f));
      }
      builder.halfTensor(
          prefix + "attn_qkv.weight",
          new long[] {dim, 3 * dim},
          randomHalves(random, 3 * dim * dim));
      builder.halfTensor(
          prefix + "attn_output.weight", new long[] {dim, dim}, randomHalves(random, dim * dim));
      builder.tensor(prefix + "ffn_norm.weight", new long[] {dim}, constantFloats(dim, 1.0f));
      builder.halfTensor(
          prefix + "ffn_up.weight", new long[] {dim, upWidth}, randomHalves(random, upWidth * dim));
      builder.halfTensor(
          prefix + "ffn_down.weight",
          new long[] {downWidth, dim},
          randomHalves(random, dim * downWidth));

      if (decision) {
        builder.tensor(prefix + "attn_norm.bias", new long[] {dim}, constantFloats(dim, 0.0f));
        builder.tensor(
            prefix + "attn_qkv.bias", new long[] {3 * dim}, constantFloats(3 * dim, 0.0f));
        builder.tensor(prefix + "attn_output.bias", new long[] {dim}, constantFloats(dim, 0.0f));
        builder.tensor(prefix + "ffn_norm.bias", new long[] {dim}, constantFloats(dim, 0.0f));
        builder.tensor(prefix + "ffn_up.bias", new long[] {upWidth}, constantFloats(upWidth, 0.0f));
        builder.tensor(prefix + "ffn_down.bias", new long[] {dim}, constantFloats(dim, 0.0f));
      }
    }
    return builder.build();
  }

  private static byte[] randomHalves(Random random, int count) {
    ByteBuffer bytes = ByteBuffer.allocate(count * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < count; index++) {
      bytes.putShort(Float.floatToFloat16((random.nextFloat() - 0.5f) * 0.25f));
    }
    return bytes.array();
  }

  private static byte[] randomFloats(Random random, int count) {
    ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(count, Float.BYTES));
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < count; index++) {
      bytes.putFloat((random.nextFloat() - 0.5f) * 0.1f);
    }
    return bytes.array();
  }

  private static byte[] constantFloats(int count, float value) {
    ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(count, Float.BYTES));
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < count; index++) {
      bytes.putFloat(value);
    }
    return bytes.array();
  }

  private static final class GgufBuilder {
    private final List<Metadata> metadata = new ArrayList<>();
    private final List<Tensor> tensors = new ArrayList<>();

    private GgufBuilder string(String key, String value) {
      metadata.add(new Metadata(key, GGUF_TYPE_STRING, value));
      return this;
    }

    private GgufBuilder uint32(String key, int value) {
      metadata.add(new Metadata(key, GGUF_TYPE_UINT32, value));
      return this;
    }

    private GgufBuilder strings(String key, List<String> values) {
      metadata.add(new Metadata(key, GGUF_TYPE_ARRAY, new StringValues(List.copyOf(values))));
      return this;
    }

    private GgufBuilder floats(String key, List<Float> values) {
      metadata.add(new Metadata(key, GGUF_TYPE_ARRAY, new FloatValues(List.copyOf(values))));
      return this;
    }

    private GgufBuilder float32(String key, float value) {
      metadata.add(new Metadata(key, GGUF_TYPE_FLOAT32, value));
      return this;
    }

    private GgufBuilder uint32s(String key, List<Integer> values) {
      metadata.add(new Metadata(key, GGUF_TYPE_ARRAY, new IntValues(List.copyOf(values))));
      return this;
    }

    private void tensor(String name, long[] shape, byte[] data) {
      tensors.add(new Tensor(name, shape.clone(), data.clone(), GGUF_TENSOR_F32));
    }

    /** An F16 matrix, which is what the batched matmul kernels accept. */
    private void halfTensor(String name, long[] shape, byte[] data) {
      tensors.add(new Tensor(name, shape.clone(), data.clone(), GGUF_TENSOR_F16));
    }

    private byte[] build() {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      writeInt(output, GGUF_MAGIC);
      writeInt(output, GGUF_VERSION);
      writeLong(output, tensors.size());
      writeLong(output, metadata.size());

      metadata.forEach(entry -> writeMetadata(output, entry));

      long tensorOffset = 0;
      for (Tensor tensor : tensors) {
        writeString(output, tensor.name());
        writeInt(output, tensor.shape().length);
        for (long dimension : tensor.shape()) {
          writeLong(output, dimension);
        }
        writeInt(output, tensor.type());
        writeLong(output, tensorOffset);
        tensorOffset = align(Math.toIntExact(tensorOffset + tensor.data().length), GGUF_ALIGNMENT);
      }

      int padding = align(output.size(), GGUF_ALIGNMENT) - output.size();
      output.writeBytes(new byte[padding]);
      // Every tensor starts at a multiple of the alignment, as GGUF readers require.
      for (int index = 0; index < tensors.size(); index++) {
        byte[] data = tensors.get(index).data();
        output.writeBytes(data);
        if (index + 1 < tensors.size()) {
          output.writeBytes(new byte[align(data.length, GGUF_ALIGNMENT) - data.length]);
        }
      }
      return output.toByteArray();
    }

    private static void writeMetadata(ByteArrayOutputStream output, Metadata metadata) {
      writeString(output, metadata.key());
      writeInt(output, metadata.type());
      switch (metadata.type()) {
        case GGUF_TYPE_STRING -> writeString(output, (String) metadata.value());
        case GGUF_TYPE_UINT32 -> writeInt(output, (Integer) metadata.value());
        case GGUF_TYPE_FLOAT32 -> writeFloat(output, (Float) metadata.value());
        case GGUF_TYPE_ARRAY -> writeArray(output, metadata.value());
        default ->
            throw new IllegalStateException(
                "Unsupported fixture metadata type: " + metadata.type());
      }
    }

    private static void writeArray(ByteArrayOutputStream output, Object value) {
      if (value instanceof StringValues strings) {
        writeInt(output, GGUF_TYPE_STRING);
        writeLong(output, strings.values().size());
        strings.values().forEach(item -> writeString(output, item));
        return;
      }
      if (value instanceof FloatValues floats) {
        writeInt(output, GGUF_TYPE_FLOAT32);
        writeLong(output, floats.values().size());
        floats.values().forEach(item -> writeFloat(output, item));
        return;
      }
      if (value instanceof IntValues ints) {
        writeInt(output, GGUF_TYPE_UINT32);
        writeLong(output, ints.values().size());
        ints.values().forEach(item -> writeInt(output, item));
        return;
      }
      throw new IllegalStateException("Unsupported fixture array: " + value.getClass().getName());
    }
  }

  private static void writeInt(ByteArrayOutputStream output, int value) {
    ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(value);
    output.writeBytes(bytes.array());
  }

  private static void writeLong(ByteArrayOutputStream output, long value) {
    ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putLong(value);
    output.writeBytes(bytes.array());
  }

  private static void writeFloat(ByteArrayOutputStream output, float value) {
    ByteBuffer bytes = ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putFloat(value);
    output.writeBytes(bytes.array());
  }

  private static void writeString(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeLong(output, bytes.length);
    output.writeBytes(bytes);
  }

  private static int align(int value, int alignment) {
    return Math.addExact(value, alignment - 1) & -alignment;
  }

  private record Metadata(String key, int type, Object value) {}

  private record StringValues(List<String> values) {}

  private record FloatValues(List<Float> values) {}

  private record IntValues(List<Integer> values) {}

  private record Tensor(String name, long[] shape, byte[] data, int type) {}
}

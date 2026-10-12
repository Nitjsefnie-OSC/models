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
package com.integrallis.models.backend.nativekernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.BatchInferenceBackend;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cold-to-compiled full-logit contract. The Bonsai task forks once per class so other real-model
 * tests cannot warm the numerical kernels before this class records its first explicit session.
 */
@Tag("slow")
class Bonsai2NativeFreshSessionSlowTest {
  private static final int[] PUBLISHER_TOKENS = {33075, 888, 279, 15217, 5388, 13, 271, 760};

  @Test
  void freshSessionsAndResetPreserveEveryLogitFromTheFirstColdSession() throws Exception {
    assertThat(Runtime.getRuntime().availableProcessors())
        .as("the cold-session fixture requires four visible CPUs")
        .isEqualTo(4);
    System.out.printf(
        "Bonsai fresh-session JVM=%s vendor=%s%n",
        System.getProperty("java.runtime.version"), System.getProperty("java.vendor"));
    Path model =
        Path.of(
                System.getProperty(
                    "models.fixtures.directory",
                    Path.of(System.getProperty("user.home"), ".jvllm", "models").toString()))
            .resolve("Ternary-Bonsai-2-27B-PTQ1_0.gguf");
    assertThat(Files.size(model)).isEqualTo(5_946_648_928L);
    var sha256 = MessageDigest.getInstance("SHA-256");
    try (var stream = new DigestInputStream(Files.newInputStream(model), sha256)) {
      stream.transferTo(OutputStream.nullOutputStream());
    }
    assertThat(HexFormat.of().formatHex(sha256.digest()))
        .isEqualTo("53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3");
    var configuration =
        new BackendConfiguration(
            Map.of(), Map.of(PureJavaBackend.MAX_CONTEXT_LENGTH_PROPERTY, "64"), List.of());
    try (var backend = RustFfmBackend.load(model, configuration)) {
      assertThat(backend.diagnostics().environment())
          .containsEntry("native-quantized-decode", "true");
      assertThat(backend.diagnostics().optimizations())
          .anySatisfy(
              optimization -> {
                assertThat(optimization.id()).isEqualTo("batched-prefill");
                assertThat(optimization.status().toString()).isEqualTo("ENABLED");
              });
      int[] prompt = backend.tokenizer().encode("The quick brown fox");
      assertThat(prompt).containsExactly(760, 3841, 13477, 37550);
      float[][] first = null;
      for (int repeat = 0; repeat < 3; repeat++) {
        float[][] actual = snapshots(backend, prompt);
        for (int step = 0; step < PUBLISHER_TOKENS.length; step++) {
          assertThat(argmax(actual[step]))
              .as("publisher token %d in fresh session %d", step, repeat)
              .isEqualTo(PUBLISHER_TOKENS[step]);
          System.out.printf(
              "Bonsai fresh-session repeat=%d step=%d fullLogitSha256=%s%n",
              repeat, step, digest(actual[step]));
        }
        if (first == null) {
          first = actual;
        } else {
          for (int step = 0; step < first.length; step++)
            assertBits(first[step], actual[step], "fresh session " + repeat + " step " + step);
        }
      }
      float[] implicit = backend.prefill(prompt, 0).clone();
      backend.reset();
      float[] reset = backend.prefill(prompt, 0).clone();
      assertBits(first[0], implicit, "implicit session");
      assertBits(first[0], reset, "reset session");
      System.out.println("BONSAI_FRESH_SESSION_FULL_LOGITS_EXACT");
    }
  }

  private static float[][] snapshots(BatchInferenceBackend backend, int[] prompt) throws Exception {
    float[][] results = new float[PUBLISHER_TOKENS.length][];
    try (var session = backend.openSession()) {
      float[] logits = backend.prefill(session, prompt, 0);
      for (int step = 0; step < results.length; step++) {
        // Match the reproducer's finite/hash check before retaining an owned copy. Backend logits
        // may reuse their backing array; no snapshot may alias the next forward call's output.
        digest(logits);
        results[step] = logits.clone();
        if (step + 1 < results.length)
          logits = backend.forward(session, argmax(logits), prompt.length + step);
      }
    }
    return results;
  }

  private static String digest(float[] values) throws Exception {
    ByteBuffer bytes =
        ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : values) {
      if (!Float.isFinite(value)) throw new AssertionError("non-finite logit");
      bytes.putInt(Float.floatToRawIntBits(value));
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
  }

  private static int argmax(float[] values) {
    int best = 0;
    for (int token = 1; token < values.length; token++)
      if (values[token] > values[best]) best = token;
    return best;
  }

  private static void assertBits(float[] expected, float[] actual, String label) {
    assertThat(actual.length).as("%s vocabulary length", label).isEqualTo(expected.length);
    for (int token = 0; token < expected.length; token++) {
      if (!Float.isFinite(actual[token])
          || Float.floatToRawIntBits(expected[token]) != Float.floatToRawIntBits(actual[token]))
        throw new AssertionError(
            label
                + " token "
                + token
                + ": expected="
                + expected[token]
                + ", actual="
                + actual[token]
                + ", expectedBits="
                + Integer.toHexString(Float.floatToRawIntBits(expected[token]))
                + ", actualBits="
                + Integer.toHexString(Float.floatToRawIntBits(actual[token])));
    }
  }
}

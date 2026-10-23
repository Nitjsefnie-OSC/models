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

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import java.util.Optional;

/**
 * One typed decision: the option chosen, the probability over options, and whether to trust it.
 *
 * <p>The head produces one logit per option. Turning those into a probability needs the calibration
 * temperature for this question's {@code type:option-count} bucket, and that step is where a
 * decision model is most easily misreported — which is why it is a separate, tested class rather
 * than three lines inside a forward pass.
 *
 * <p>{@link #uncalibrated()} is the part worth reading before the probability. The artifact behind
 * {@code ggml-org/Laya-GGUF} declares {@code choice:11+ = 0.100583}, a temperature that sharpens
 * the logits about tenfold; the model's own reference implementation refuses it, clamps to 0.5 and
 * warns that the affected entries are uncalibrated. So a twelve-option question here yields a
 * ranked answer whose probability is not a probability anybody fitted, and this record says so
 * rather than publishing the number as though it were.
 */
public record ModernBertDecisionAnswer(
    QuestionType type,
    int chosen,
    float[] probabilities,
    float[] logits,
    String temperatureBucket,
    float temperature,
    boolean uncalibrated) {

  public ModernBertDecisionAnswer {
    probabilities = probabilities.clone();
    logits = logits.clone();
  }

  /**
   * Decodes one question's logits.
   *
   * @param type the question type, which selects both the type embedding row and the bucket family
   * @param logits one logit per option, in the order the options were presented
   * @param decision the head's declared calibration, or empty for an artifact without one
   */
  public static ModernBertDecisionAnswer of(
      QuestionType type, float[] logits, Optional<ModernBertConfig.Decision> decision) {
    if (type == null) {
      throw new IllegalArgumentException("type must not be null");
    }
    if (logits == null || logits.length == 0) {
      throw new IllegalArgumentException("a question needs at least one option logit");
    }
    for (float logit : logits) {
      if (!Float.isFinite(logit)) {
        throw new IllegalArgumentException("option logits must be finite: " + logit);
      }
    }
    String bucket = ModernBertConfig.temperatureBucket(type, logits.length);
    // An artifact with no decision head declares no temperatures, and 1.0 is then the honest
    // reading of the logits rather than a fitted one. It is still reported as uncalibrated.
    float temperature = decision.flatMap(head -> head.temperatureFor(bucket)).orElse(1.0f);
    boolean uncalibrated =
        decision.map(head -> head.isUncalibrated(bucket)).orElse(true)
            || decision.flatMap(head -> head.temperatureFor(bucket)).isEmpty();

    float[] scaled = new float[logits.length];
    for (int option = 0; option < logits.length; option++) {
      scaled[option] = logits[option] / temperature;
    }
    float[] probabilities = softmax(scaled);
    int chosen = 0;
    for (int option = 1; option < probabilities.length; option++) {
      if (probabilities[option] > probabilities[chosen]) {
        chosen = option;
      }
    }
    return new ModernBertDecisionAnswer(
        type, chosen, probabilities, logits.clone(), bucket, temperature, uncalibrated);
  }

  /** The probability of the chosen option, which is what a confidence gate reads. */
  public float confidence() {
    return probabilities[chosen];
  }

  /**
   * The gap between the top two options, which separates a decided question from a near-tie.
   *
   * <p>A single-option question is decided by definition, so the margin is its whole probability.
   */
  public float margin() {
    if (probabilities.length == 1) {
      return probabilities[0];
    }
    float best = Float.NEGATIVE_INFINITY;
    float second = Float.NEGATIVE_INFINITY;
    for (float probability : probabilities) {
      if (probability > best) {
        second = best;
        best = probability;
      } else if (probability > second) {
        second = probability;
      }
    }
    return best - second;
  }

  private static float[] softmax(float[] values) {
    float max = Float.NEGATIVE_INFINITY;
    for (float value : values) {
      max = Math.max(max, value);
    }
    double total = 0.0;
    double[] exponentials = new double[values.length];
    for (int index = 0; index < values.length; index++) {
      exponentials[index] = Math.exp(values[index] - max);
      total += exponentials[index];
    }
    float[] probabilities = new float[values.length];
    for (int index = 0; index < values.length; index++) {
      probabilities[index] = (float) (exponentials[index] / total);
    }
    return probabilities;
  }

  @Override
  public float[] probabilities() {
    return probabilities.clone();
  }

  @Override
  public float[] logits() {
    return logits.clone();
  }
}

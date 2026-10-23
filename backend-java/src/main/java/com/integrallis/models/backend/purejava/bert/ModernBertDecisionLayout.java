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

import java.util.Arrays;

/**
 * Position ids and option ids that make a typed-decision encoder blind to the order of its options.
 *
 * <p>A decision question is encoded as {@code [CLS] <type> question: <instructions> [SEP] [MASK]
 * opt0 [MASK] opt1 … [SEP] <state>}, and each option's logit is read at its {@code [MASK]}. Laid
 * out sequentially, option {@code s} sits after option {@code s-1} and attends to it, so *where* an
 * option is listed changes its embedding. The reference implementation measures that cost on five
 * identical options: their logits spread by 3.59.
 *
 * <p>So every option starts at the same position — the first one after the instruction's {@code
 * [SEP]} — the head's closing {@code [SEP]} and the state resume after the longest option, and
 * {@link #isolates(int, int)} stops one option's tokens from attending to another's. Together,
 * reordering the options only reorders the marker embeddings.
 *
 * <p>This is pure index arithmetic, which is the only reason it can be checked before a single
 * weight is loaded: the expected ids for eight real questions are in {@code
 * modernbert/laya-reference.json}, produced by the reference implementation.
 */
public record ModernBertDecisionLayout(int[] positionIds, int[] optionIds, int[] markers) {

  public ModernBertDecisionLayout {
    positionIds = positionIds.clone();
    optionIds = optionIds.clone();
    markers = markers.clone();
  }

  /**
   * Builds the layout for one encoded question.
   *
   * @param markers index of each option's {@code [MASK]}, ascending, as the head emitted them
   * @param headLength tokens in the question half, so {@code headLength - 1} is its closing {@code
   *     [SEP]} and where the last option's span ends
   * @param sequenceLength total tokens, head plus state
   */
  public static ModernBertDecisionLayout of(int[] markers, int headLength, int sequenceLength) {
    if (markers == null) {
      throw new IllegalArgumentException("markers must not be null");
    }
    if (sequenceLength <= 0) {
      throw new IllegalArgumentException("sequenceLength must be > 0: " + sequenceLength);
    }
    int[] positionIds = new int[sequenceLength];
    int[] optionIds = new int[sequenceLength];
    if (markers.length == 0) {
      // No options: ordinary sequential positions and no option to isolate. The reference takes
      // the same branch rather than treating it as an error, because a state-only encode is a
      // legitimate call.
      for (int index = 0; index < sequenceLength; index++) {
        positionIds[index] = index;
      }
      return new ModernBertDecisionLayout(positionIds, optionIds, markers.clone());
    }
    if (headLength < 1 || headLength > sequenceLength) {
      throw new IllegalArgumentException(
          "headLength must be within the sequence: " + headLength + " of " + sequenceLength);
    }
    for (int option = 1; option < markers.length; option++) {
      if (markers[option] <= markers[option - 1]) {
        throw new IllegalArgumentException("markers must ascend: " + Arrays.toString(markers));
      }
    }
    int start = markers[0];
    if (start < 0 || markers[markers.length - 1] >= headLength) {
      throw new IllegalArgumentException(
          "markers must lie inside the head: "
              + Arrays.toString(markers)
              + " against headLength "
              + headLength);
    }

    // Everything before the first option keeps its own position: the instruction is shared.
    for (int index = 0; index < start; index++) {
      positionIds[index] = index;
    }
    // Each option restarts at `start`, so option s's j-th token has position start + j.
    int longest = 0;
    for (int option = 0; option < markers.length; option++) {
      int from = markers[option];
      int to = option + 1 < markers.length ? markers[option + 1] : headLength - 1;
      for (int index = from; index < to; index++) {
        positionIds[index] = start + (index - from);
        optionIds[index] = option + 1;
      }
      longest = Math.max(longest, to - from);
    }
    // The closing [SEP] and the state continue after the longest option, not after the last one:
    // positions have to be shared across options for the layout to be order-blind, so the tail
    // cannot simply resume where the final option happens to end.
    int tail = start + longest;
    for (int index = headLength - 1; index < sequenceLength; index++) {
      positionIds[index] = tail + (index - (headLength - 1));
    }
    return new ModernBertDecisionLayout(positionIds, optionIds, markers.clone());
  }

  /**
   * Whether attention from {@code query} to {@code key} must be masked out.
   *
   * <p>Only option-to-other-option attention is blocked. A shared token ({@code optionIds == 0} —
   * the instruction, the closing {@code [SEP]}, the state) attends to and is attended from
   * everything, because the state is what the options are being scored against.
   */
  public boolean isolates(int query, int key) {
    int queryOption = optionIds[query];
    int keyOption = optionIds[key];
    // Symmetric, and measured to be so. A one-directional rule that also stopped the shared
    // tokens from reading the options was tried on 2026-10-22 and is wrong: against the
    // reference's logits it was three to seven times worse at every option count (4.20 against
    // 0.58 at twelve options). The state does read the options; what the layout forbids is one
    // option reading another.
    return queryOption != 0 && keyOption != 0 && queryOption != keyOption;
  }

  /** How many options this layout carries. */
  public int options() {
    return markers.length;
  }

  @Override
  public int[] positionIds() {
    return positionIds.clone();
  }

  @Override
  public int[] optionIds() {
    return optionIds.clone();
  }

  @Override
  public int[] markers() {
    return markers.clone();
  }
}

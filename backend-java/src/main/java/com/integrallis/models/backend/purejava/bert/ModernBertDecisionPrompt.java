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
import com.integrallis.models.backend.purejava.tokenizer.GgufTokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The head prompt a {@code modern-bert} decision model expects, and where each option's marker
 * lands in it.
 *
 * <p>This is <b>transcription, not invention</b>: the artifact carries the template itself, under
 * {@code tokenizer.chat_template.systemone}, and this renders exactly that:
 *
 * <pre>
 *   [CLS]{type} question: {instructions}[SEP]
 *     {per option} [MASK] + one space + the option's rendering
 *   [SEP]{state}[SEP]
 * </pre>
 *
 * <p>The per-type option rendering is the template's own, and the three branches differ:
 *
 * <ul>
 *   <li>{@code choice}: {@code key} then {@code ": " + description} when there is one
 *   <li>{@code score}: {@code "level " + key + ": " + description}
 *   <li>{@code noul}: {@code key + ": " + description}, and with no description the template
 *       supplies the words itself — {@code "yes, the statement holds"} for {@code true} and {@code
 *       "no, the statement does not hold"} for anything else
 * </ul>
 *
 * <p><b>The special tokens are inserted by id, never by spelling.</b> {@code [CLS]}, {@code [SEP]}
 * and {@code [MASK]} appear in the template as literal text, but handing that text to a BPE
 * tokenizer would encode the brackets as ordinary characters. Each text run is encoded on its own
 * and the markers are placed around it.
 *
 * <p>{@link GgufTokenizer#encode} adds this artifact's configured boundary pair — {@code [CLS]} at
 * the front and {@code [SEP]} at the back — around whatever it is given, so each run's own
 * boundaries are dropped before assembly. Verified against the reference's token ids for all eight
 * cases rather than assumed.
 */
public final class ModernBertDecisionPrompt {

  /** An option as the template sees it: a key, and a description that may be absent. */
  public record Option(String key, String description) {
    public Option {
      key = Objects.requireNonNull(key, "key").strip();
      if (key.isEmpty()) {
        throw new IllegalArgumentException("option key must not be blank");
      }
    }

    /** An option with no description, which only {@code noul} and {@code choice} render. */
    public static Option of(String key) {
      return new Option(key, null);
    }
  }

  /** The encoded prompt: token ids, and the index of each option's {@code [MASK]}. */
  public record Encoded(int[] ids, int[] markers, int headLength) {}

  private final GgufTokenizer tokenizer;
  private final int cls;
  private final int sep;
  private final int mask;
  private final int maximumHeadTokens;
  private final int maximumOptionTokens;

  /** The option-token cap the reference's tokenizer applies; not declared in the header. */
  public static final int DEFAULT_MAXIMUM_OPTION_TOKENS = 48;

  public ModernBertDecisionPrompt(GgufTokenizer tokenizer, ModernBertConfig config) {
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    Objects.requireNonNull(config, "config");
    this.cls = tokenizer.tokenId("[CLS]");
    this.sep = tokenizer.tokenId("[SEP]");
    this.mask = tokenizer.tokenId("[MASK]");
    if (cls < 0 || sep < 0 || mask < 0) {
      throw new IllegalArgumentException(
          "artifact lacks [CLS]/[SEP]/[MASK]: " + cls + ", " + sep + ", " + mask);
    }
    this.maximumHeadTokens =
        config.decision().map(ModernBertConfig.Decision::maxHeadTokens).orElse(192);
    this.maximumOptionTokens = DEFAULT_MAXIMUM_OPTION_TOKENS;
  }

  /**
   * Encodes one typed question over one state.
   *
   * @param type the question type, which picks the option rendering
   * @param instructions the question as the caller phrases it
   * @param options the options, in the order their logits will come back
   * @param state the text being decided about
   * @return the token ids and each option's marker index
   */
  public Encoded encode(
      QuestionType type, String instructions, List<Option> options, String state) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(state, "state");
    if (options.isEmpty()) {
      throw new IllegalArgumentException("a decision needs at least one option");
    }

    List<Integer> ids = new ArrayList<>();
    int[] markers = new int[options.size()];
    ids.add(cls);
    for (int token : inner(type.key() + " question: " + instructions)) {
      ids.add(token);
    }
    ids.add(sep);

    for (int index = 0; index < options.size(); index++) {
      markers[index] = ids.size();
      ids.add(mask);
      // The leading space is the template's, between "[MASK]" and the option text, and it changes
      // the first token under a byte-level BPE.
      int[] option = inner(" " + render(type, options.get(index)));
      for (int position = 0; position < Math.min(option.length, maximumOptionTokens); position++) {
        ids.add(option[position]);
      }
    }
    ids.add(sep);
    int headLength = ids.size();
    if (headLength > maximumHeadTokens) {
      throw new IllegalArgumentException(
          "head is "
              + headLength
              + " tokens, over the artifact's declared budget of "
              + maximumHeadTokens
              + "; shorten the instructions or the options");
    }

    for (int token : inner(state)) {
      ids.add(token);
    }
    ids.add(sep);

    int[] encoded = new int[ids.size()];
    for (int index = 0; index < encoded.length; index++) {
      encoded[index] = ids.get(index);
    }
    return new Encoded(encoded, markers, headLength);
  }

  /** The template's per-type option rendering, branch for branch. */
  private static String render(QuestionType type, Option option) {
    String description = option.description();
    boolean described = description != null && !description.isBlank();
    return switch (type) {
      case CHOICE -> described ? option.key() + ": " + description : option.key();
      case SCORE -> "level " + option.key() + ": " + description;
      case NOUL ->
          option.key()
              + ": "
              + (described
                  ? description
                  : "true".equalsIgnoreCase(option.key())
                      ? "yes, the statement holds"
                      : "no, the statement does not hold");
    };
  }

  /** One text run's tokens, without the boundary pair the tokenizer wraps around it. */
  private int[] inner(String text) {
    int[] encoded = tokenizer.encode(text);
    int from = encoded.length > 0 && encoded[0] == cls ? 1 : 0;
    int to =
        encoded.length > from && encoded[encoded.length - 1] == sep
            ? encoded.length - 1
            : encoded.length;
    int[] stripped = new int[to - from];
    System.arraycopy(encoded, from, stripped, 0, stripped.length);
    return stripped;
  }
}

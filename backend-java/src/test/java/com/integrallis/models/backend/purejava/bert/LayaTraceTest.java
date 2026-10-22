package com.integrallis.models.backend.purejava.bert;

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Prints the Java per-stage fingerprint, to line up against oracle/INTERMEDIATES-*.txt. */
@Tag("model-fixture")
class LayaTraceTest {

  private static final Path ARTIFACT =
      Path.of(System.getProperty("user.home"), ".jvllm", "models", "Laya-BF16.gguf");

  @Test
  void trace() throws Exception {
    if (!Files.isRegularFile(ARTIFACT)) {
      System.out.println("TRACE no artifact");
      return;
    }
    String target = System.getProperty("laya.trace.case", "has_invoice");
    String json;
    try (var in = LayaTraceTest.class.getResourceAsStream("/modernbert/laya-reference.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Pattern block = Pattern.compile(
        "\\{\\s*\"question\":\\s*\"([^\"]+)\",\\s*\"type\":\\s*\"([^\"]+)\".*?"
            + "\"ids\":\\s*\\[([^]]*)].*?\"markers\":\\s*\\[([^]]*)].*?"
            + "\"headLength\":\\s*(\\d+).*?\"sequenceLength\":\\s*(\\d+).*?"
            + "\"rawLogits\":\\s*\\[([^]]*)]", Pattern.DOTALL);
    Matcher m = block.matcher(json);
    while (m.find()) {
      if (!m.group(1).equals(target)) {
        continue;
      }
      int[] ids = ints(m.group(3));
      int[] markers = ints(m.group(4));
      int headLength = Integer.parseInt(m.group(5));
      int sequenceLength = Integer.parseInt(m.group(6));
      QuestionType type = QuestionType.valueOf(m.group(2).toUpperCase(Locale.ROOT));
      System.out.printf("TRACE # case=%s tokens=%d markers=%s%n",
          target, ids.length, Arrays.toString(markers));

      try (Arena arena = Arena.ofConfined()) {
        var file = GgufParser.parse(ARTIFACT, arena);
        var config = ModernBertConfig.fromMetadata(file.metadata());
        var weights = ModernBertWeights.load(file, config);
        List<String> trace = new ArrayList<>();
        ModernBertDecisionForwardPass.TRACE = trace;
        try {
          var layout = ModernBertDecisionLayout.of(markers, headLength, sequenceLength);
          float[] logits =
              new ModernBertDecisionForwardPass(config, weights).logits(ids, layout, type);
          trace.forEach(line -> System.out.println("TRACE " + line));
          System.out.println("TRACE # raw logits: " + Arrays.toString(logits));
        } finally {
          ModernBertDecisionForwardPass.TRACE = null;
        }
      }
      return;
    }
    System.out.println("TRACE case not found: " + target);
  }

  private static int[] ints(String csv) {
    String[] p = csv.trim().split(",");
    int[] v = new int[p.length];
    for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i].trim());
    return v;
  }
}

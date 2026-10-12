package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.purejava.PureJavaBackend;
import java.nio.file.Path;
import java.util.Map;

/** Export the exact existing Models tokenizer inputs for the fixed oracle cases. */
public final class OracleInputs {
  public static void main(String[] args) throws Exception {
    var tasks = GdnCampaign.JSON.readTree(Path.of(args[1]).toFile());
    try (var backend = PureJavaBackend.load(Path.of(args[0]))) {
      for (var task : tasks) {
        String id = task.path("id").asText();
        if (id.equals("original-routing-06-0") || id.equals("snips-AddToPlaylist-0"))
          GdnCampaign.emit(
              Map.of(
                  "id",
                  id,
                  "prompt",
                  task.path("prompt").asText(),
                  "tokens",
                  backend.tokenizer().encode(task.path("prompt").asText())));
      }
    }
  }
}

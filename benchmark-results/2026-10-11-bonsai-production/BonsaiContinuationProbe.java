import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.api.*;
import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import java.nio.file.Path;
import java.util.*;

/** The independently recorded publisher continuation through either production backend. */
class BonsaiContinuationProbe {
  public static void main(String[] args) throws Exception {
    var configuration = new BackendConfiguration(Map.of(),
        Map.of(PureJavaBackend.MAX_CONTEXT_LENGTH_PROPERTY, "64"), List.of());
    try (BatchInferenceBackend backend = args[1].equals("rust-ffm")
        ? RustFfmBackend.load(Path.of(args[0]), configuration)
        : PureJavaBackend.load(Path.of(args[0]), configuration)) {
      int[] prompt = backend.tokenizer().encode("The quick brown fox");
      if (!Arrays.equals(prompt, new int[] {760, 3841, 13477, 37550})) throw new AssertionError("prompt tokens");
      int[] expected = {33075, 888, 279, 15217, 5388, 13, 271, 760};
      float[] logits = backend.prefill(prompt, 0);
      for (int step = 0; step < expected.length; step++) {
        if (argmax(logits) != expected[step]) throw new AssertionError("oracle token " + step);
        if (step + 1 < expected.length) logits = backend.forward(expected[step], prompt.length + step);
      }
      try (var session = backend.openSession()) {
        if (argmax(backend.prefill(session, prompt, 0)) != expected[0]) throw new AssertionError("fresh session");
      }
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),
          Map.of("backend", args[1], "expectedTokens", expected, "allTokensMatch", true,
              "freshSessionMatches", true, "publisherRevision", "e8fd209c975c80aa7fb36a8423571ff3b1b7e955"));
    }
  }
  private static int argmax(float[] logits) {
    int best = 0;
    for (int i = 0; i < logits.length; i++) {
      if (!Float.isFinite(logits[i])) throw new AssertionError("nonfinite logit " + i);
      if (logits[i] > logits[best]) best = i;
    }
    return best;
  }
}

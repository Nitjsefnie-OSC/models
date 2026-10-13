import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Keep Java records separate from asynchronous VM assembly output. */
public final class CompilerEntry {
  public static void main(String[] args) throws Exception {
    try (var records = new PrintStream(System.getProperty("width.output"), StandardCharsets.UTF_8)) {
      System.setOut(records);
      WidthBench.main(args);
    }
  }
}

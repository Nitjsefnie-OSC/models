import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import com.integrallis.models.backend.purejava.gguf.GgufTensorType;
import com.integrallis.models.backend.purejava.ops.TensorOps;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Actual packed tensors, exact outputs, and paired ABBA wall-clock projection measurements. */
class PtqProjectionBenchmark {
  public static void main(String[] args) throws Exception {
    var records = new ArrayList<Map<String, Object>>();
    try (var arena = Arena.ofShared();
        var kernel = RustGgufBatchedMatrixKernel.open(Path.of(args[1]), 16)) {
      var file = GgufParser.parse(Path.of(args[0]), arena);
      for (String name : List.of("blk.0.ffn_up.weight", "blk.0.ffn_down.weight", "output.weight")) {
        var tensor = file.getTensor(name);
        int cols = Math.toIntExact(tensor.shape()[0]);
        int rows = Math.toIntExact(tensor.shape()[1]);
        if (tensor.info().type() != GgufTensorType.PTQ1_0) throw new AssertionError(name);
        float[] input = new float[cols];
        var random = new Random(143);
        for (int i = 0; i < cols; i++) input[i] = random.nextFloat() * 2 - 1;
        float[] javaOutput = new float[rows], nativeOutput = new float[rows];
        Runnable javaRun = () -> TensorOps.ggufMatmul(javaOutput, input, tensor.dataSegment(), GgufTensorType.PTQ1_0, rows, cols);
        Runnable nativeRun = () -> kernel.multiply(nativeOutput, input, tensor.dataSegment(), GgufTensorType.PTQ1_0, 1, rows, cols);
        for (int i = 0; i < 3; i++) { javaRun.run(); nativeRun.run(); }
        if (!Arrays.equals(javaOutput, nativeOutput)) throw new AssertionError("parity: " + name);
        var trials = new ArrayList<Map<String, Object>>();
        for (int repeat = 0; repeat < 3; repeat++) {
          for (String arm : List.of("java", "native", "native", "java")) {
            Thread.sleep(100); // Let the previous arm's worker poll budget expire.
            long start = System.nanoTime();
            (arm.equals("java") ? javaRun : nativeRun).run();
            trials.add(Map.of("arm", arm, "millis", (System.nanoTime() - start) / 1e6));
            if (!Arrays.equals(javaOutput, nativeOutput)) throw new AssertionError("parity: " + name);
          }
        }
        var record = new LinkedHashMap<String, Object>();
        record.put("tensor", name); record.put("rows", rows); record.put("columns", cols);
        record.put("bitExact", true); record.put("trials", trials); records.add(record);
      }
    }
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), records);
  }
}

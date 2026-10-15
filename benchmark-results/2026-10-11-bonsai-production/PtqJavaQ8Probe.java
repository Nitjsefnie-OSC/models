import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.nativekernel.PtqQ8Oracle;
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
class PtqJavaQ8Probe {
  public static void main(String[] args) throws Exception {
    var records = new ArrayList<Map<String, Object>>();
    try (var arena = Arena.ofShared();
        var kernel = RustGgufBatchedMatrixKernel.open(Path.of(args[1]), args.length > 3 ? Integer.parseInt(args[3]) : Runtime.getRuntime().availableProcessors())) {
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
        verify(tensor.dataSegment(), input, javaOutput, nativeOutput, cols);
        var trials = new ArrayList<Map<String, Object>>();
        for (int repeat = 0; repeat < 3; repeat++) {
          for (String arm : List.of("java", "native", "native", "java")) {
            Thread.sleep(100); // Let the previous arm's worker poll budget expire.
            long start = System.nanoTime();
            (arm.equals("java") ? javaRun : nativeRun).run();
            trials.add(Map.of("arm", arm, "millis", (System.nanoTime() - start) / 1e6));
            verify(tensor.dataSegment(), input, javaOutput, nativeOutput, cols);
          }
        }
        var record = new LinkedHashMap<String, Object>();
        record.put("tensor", name); record.put("rows", rows); record.put("columns", cols);
        record.put("javaReferenceContract", "PTQ1_0 x Q8_0");
        record.put("nativeContract", "PTQ1_0 x Q8_0");
        record.put("javaNativeBitExact", true);
        record.put("sampledIndependentQ8OracleBitExact", true);
        double dot = 0, aa = 0, bb = 0, maxError = 0;
        for (int r = 0; r < rows; r++) {
          dot += (double) javaOutput[r] * nativeOutput[r];
          aa += (double) javaOutput[r] * javaOutput[r];
          bb += (double) nativeOutput[r] * nativeOutput[r];
          maxError = Math.max(maxError, Math.abs((double) javaOutput[r] - nativeOutput[r]));
        }
        record.put("q8Cosine", dot / Math.sqrt(aa * bb));
        record.put("q8MaxAbsoluteError", maxError);
        record.put("trials", trials); records.add(record);
      }
    }
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), records);
  }
  private static void verify(java.lang.foreign.MemorySegment weights,
      float[] input, float[] javaOutput, float[] nativeOutput, int cols) {
    if (!Arrays.equals(javaOutput, nativeOutput)) throw new AssertionError("Java/native Q8 contract parity");
    long rowBytes = (long) cols / 128 * 28;
    for (int center : new int[] {0, nativeOutput.length / 2, nativeOutput.length - 3}) {
      for (int row = center; row < Math.min(center + 3, nativeOutput.length); row++) {
        float expected = PtqQ8Oracle.multiply(weights.asSlice(row * rowBytes, rowBytes), input, 1, 1, cols)[0];
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(nativeOutput[row]))
          throw new AssertionError("Q8 oracle parity row " + row);
      }
    }
  }

}

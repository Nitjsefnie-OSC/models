package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import java.nio.file.Path;
import java.util.Arrays;

/** Prefix parity plus rejection of overlapping grouped state ownership before dispatch. */
public final class ExclusiveProof {
  public static void main(String[] args) throws Exception {
    try (var library = NativeKernelLibrary.open(Path.of(args[0]))) {
      GdnProof.libraries.put("native-aligned", library);
      int cases = 0;
      for (int d : new int[] {7, 128, 256})
        for (int tokens : new int[] {1, 7, 8, 18, 64, 128, 512})
          for (boolean nonzero : new boolean[] {false, true}) {
            var input = GdnProof.synthetic(tokens, d, nonzero);
            var expected = GdnProof.run("java", input);
            var actual = GdnProof.run("native-aligned", input);
            if (!Arrays.equals(expected.output(), actual.output())
                || !Arrays.equals(expected.state(), actual.state()))
              throw new AssertionError("prefix parity: dimension=" + d + " tokens=" + tokens);
            cases++;
          }
      var input = GdnProof.synthetic(3, 7, true);
      for (int[] slots : new int[][] {{1, 1, 3}, {-1, 1, 3}, {0, 1, 7}}) {
        float[] state = new float[7 * input.initial().length];
        float[] out = new float[input.v().length];
        Arrays.fill(state, 3.25f);
        Arrays.fill(out, 7.75f);
        float[] originalState = state.clone(), originalOut = out.clone();
        try {
          GroupProof.apply(
              "native-aligned-parallel",
              input,
              slots,
              state,
              out,
              new GroupProof.Scratch(input),
              library);
          throw new AssertionError("invalid slots accepted: " + Arrays.toString(slots));
        } catch (IllegalStateException expected) {
          if (!expected.getMessage().endsWith("invalid shape")) throw expected;
        }
        if (!Arrays.equals(state, originalState) || !Arrays.equals(out, originalOut))
          throw new AssertionError("invalid input mutated buffers");
      }
      System.out.println(
          "PASS " + cases + " exact prefixes; 3 invalid slot tables rejected without mutation");
    }
  }
}

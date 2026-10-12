package com.integrallis.models.backend.nativekernel;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Exact old/new attention outputs and scores, including offset guards and idle workers. */
public final class AttentionOwnershipProof {
  static float[] random(SplittableRandom random, int length) {
    float[] values = new float[length];
    for (int n = 0; n < length; n++) values[n] = random.nextFloat() - .5f;
    return values;
  }

  public static void main(String[] args) {
    int cases = 0;
    for (int threads : new int[] {1, 4, 8}) {
      try (var original = NativeKernelLibrary.open(Path.of(args[0]), threads);
          var fixed = NativeKernelLibrary.open(Path.of(args[1]), threads)) {
        if (original.threadCount() != threads || fixed.threadCount() != threads)
          throw new AssertionError("worker count did not execute");
        System.out.println("THREADS " + threads + " decode=" + fixed.decodeThreadCount());
        for (int kv : new int[] {1, 2, 5})
          for (int group : new int[] {1, 3})
            for (int kd : new int[] {7, 64, 128})
              for (int vd : new int[] {9, 64})
                for (int pa : new int[] {0, 1, 37})
                  for (int pb : new int[] {0, 5}) {
                    if (pa + pb == 0) continue;
                    int heads = kv * group, total = pa + pb;
                    var random = new SplittableRandom(912);
                    float[] q = random(random, 3 + heads * kd + 4);
                    float[] ka = random(random, 5 + pa * kv * kd + 4);
                    float[] va = random(random, 7 + pa * kv * vd + 4);
                    float[] kb = random(random, 2 + pb * kv * kd + 4);
                    float[] vb = random(random, 1 + pb * kv * vd + 4);
                    for (int repeat = 0; repeat < 3; repeat++) {
                      float[][] output = {
                        new float[2 + heads * vd + 4], new float[2 + heads * vd + 4]
                      };
                      float[][] scores = {
                        new float[heads * total + 4], new float[heads * total + 4]
                      };
                      NativeKernelLibrary[] libraries = {original, fixed};
                      for (int arm = 0; arm < 2; arm++) {
                        Arrays.fill(output[arm], 931.25f);
                        Arrays.fill(scores[arm], 937.25f);
                        libraries[arm].groupedAttentionF32(
                            q,
                            3,
                            ka,
                            5,
                            va,
                            7,
                            pa,
                            kb,
                            2,
                            vb,
                            1,
                            pb,
                            output[arm],
                            2,
                            scores[arm],
                            kv * kd,
                            kv * vd,
                            kd,
                            vd,
                            heads,
                            kv,
                            .015625f);
                        for (int n = 0; n < output[arm].length; n++)
                          if ((n < 2 || n >= 2 + heads * vd) && output[arm][n] != 931.25f)
                            throw new AssertionError("output guard changed");
                        for (int n = heads * total; n < scores[arm].length; n++)
                          if (scores[arm][n] != 937.25f)
                            throw new AssertionError("score guard changed");
                      }
                      if (!Arrays.equals(output[0], output[1])
                          || !Arrays.equals(scores[0], scores[1]))
                        throw new AssertionError(
                            "attention arithmetic changed: threads="
                                + threads
                                + " kv="
                                + kv
                                + " group="
                                + group
                                + " kd="
                                + kd
                                + " vd="
                                + vd
                                + " spans="
                                + pa
                                + "+"
                                + pb);
                    }
                    cases++;
                  }
      }
    }
    System.out.println(
        "PASS " + cases + " cases x 3 repeats, exact outputs/scores and guard preservation");
  }
}

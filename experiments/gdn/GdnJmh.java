package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Sustained, forked measurements at the real Qwen3.5-4B head geometry. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class GdnJmh {
  @Param({"java", "java-head", "java-fused", "java-parallel", "java-polling", "native-aligned"})
  public String arm;

  @Param({"1", "18", "32", "128", "512"})
  public int tokens;

  private GdnProof.Input input;
  private GdnProof.Result coldReference;
  private float[] state, output;

  @Setup(Level.Trial)
  public void setup() {
    int kh = 16, vh = 32, d = 128;
    var random = new SplittableRandom(20261011);
    float[] q = GdnProof.random(random, tokens * kh * d, .25f),
        k = GdnProof.random(random, tokens * kh * d, .25f),
        v = GdnProof.random(random, tokens * vh * d, .25f),
        g = new float[tokens * vh],
        b = new float[tokens * vh];
    for (int n = 0; n < g.length; n++) {
      g[n] = -.05f - random.nextFloat() * .2f;
      b[n] = .2f + random.nextFloat() * .7f;
    }
    input =
        new GdnProof.Input(
            q, k, v, g, b, GdnProof.random(random, vh * d * d, .01f), tokens, kh, vh, d, d);
    state = input.initial().clone();
    output = new float[tokens * vh * d];
    if (arm.startsWith("native"))
      GdnProof.libraries.put(
          arm, NativeKernelLibrary.open(Path.of(System.getProperty("gdn.nativeLibrary"))));
    var reference = GdnProof.run("java", input);
    coldReference = reference;
    var candidate = GdnProof.run(arm, input);
    if (!java.util.Arrays.equals(reference.output(), candidate.output())
        || !java.util.Arrays.equals(reference.state(), candidate.state()))
      throw new AssertionError("arithmetic changed");
  }

  @Setup(Level.Invocation)
  public void reset() {
    System.arraycopy(input.initial(), 0, state, 0, state.length);
  }

  @TearDown(Level.Trial)
  public void close() {
    try {
      var hotReference = GdnProof.run("java", input);
      var hotCandidate = GdnProof.run(arm, input);
      if (!java.util.Arrays.equals(coldReference.output(), hotReference.output())
          || !java.util.Arrays.equals(coldReference.state(), hotReference.state()))
        throw new AssertionError("reference changed between cold and compiled execution");
      if (!java.util.Arrays.equals(hotReference.output(), hotCandidate.output())
          || !java.util.Arrays.equals(hotReference.state(), hotCandidate.state()))
        throw new AssertionError("compiled candidate changed arithmetic");
    } finally {
      for (var lib : GdnProof.libraries.values()) lib.close();
    }
  }

  @Benchmark
  public void recurrence(Blackhole bh) {
    GdnProof.apply(arm, input, state, output);
    bh.consume(output);
    bh.consume(state);
  }
}

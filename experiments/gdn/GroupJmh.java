package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class GroupJmh {
  @Param({"java", "java-tiled-stage", "native-aligned-parallel"})
  public String arm;

  @Param({"1", "3", "8", "16"})
  public int branches;

  private GdnProof.Input input;
  private float[] initial, state, output, expectedState, expectedOutput;
  private int[] slots;
  private GroupProof.Scratch scratch;
  private NativeKernelLibrary library;

  @Setup(Level.Trial)
  public void setup() {
    int kh = 16, vh = 32, d = 128;
    var random = new SplittableRandom(20261011);
    float[] gates = new float[branches * vh], beta = new float[branches * vh];
    for (int n = 0; n < gates.length; n++) {
      gates[n] = -.05f - random.nextFloat() * .2f;
      beta[n] = .2f + random.nextFloat() * .7f;
    }
    initial = GdnProof.random(random, (2 * branches + 1) * vh * d * d, .01f);
    input =
        new GdnProof.Input(
            GdnProof.random(random, branches * kh * d, .25f),
            GdnProof.random(random, branches * kh * d, .25f),
            GdnProof.random(random, branches * vh * d, .25f),
            gates,
            beta,
            initial,
            branches,
            kh,
            vh,
            d,
            d);
    slots = new int[branches];
    for (int n = 0; n < branches; n++) slots[n] = 2 * (branches - n) - 1;
    scratch = new GroupProof.Scratch(input);
    state = initial.clone();
    output = new float[branches * vh * d];
    if (arm.startsWith("native"))
      library = NativeKernelLibrary.open(Path.of(System.getProperty("gdn.nativeLibrary")));
    expectedState = initial.clone();
    expectedOutput = new float[output.length];
    GroupProof.apply("java", input, slots, expectedState, expectedOutput, scratch, library);
    check();
  }

  private void check() {
    reset();
    GroupProof.apply(arm, input, slots, state, output, scratch, library);
    if (!Arrays.equals(expectedState, state) || !Arrays.equals(expectedOutput, output))
      throw new AssertionError("grouped candidate changed arithmetic or inactive state");
  }

  @Setup(Level.Invocation)
  public void reset() {
    System.arraycopy(initial, 0, state, 0, state.length);
  }

  @TearDown(Level.Trial)
  public void close() {
    try {
      float[] hotState = initial.clone(), hotOutput = new float[output.length];
      GroupProof.apply("java", input, slots, hotState, hotOutput, scratch, library);
      if (!Arrays.equals(expectedState, hotState) || !Arrays.equals(expectedOutput, hotOutput))
        throw new AssertionError("grouped reference changed after compilation");
      check();
    } finally {
      if (library != null) library.close();
    }
  }

  @Benchmark
  public void recurrence(Blackhole bh) {
    GroupProof.apply(arm, input, slots, state, output, scratch, library);
    bh.consume(state);
    bh.consume(output);
  }
}

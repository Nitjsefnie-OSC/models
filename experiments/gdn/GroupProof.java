package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.NativeKernelLibrary;
import com.integrallis.vectors.core.GgufStagePlan;
import java.nio.file.Path;
import java.util.*;

/** Grouped rows and scattered state slots; the tiled arithmetic is unchanged. */
public final class GroupProof {
  record Scratch(float[] q, float[] k, float[] memory, float[] delta, float[] rowOut) {
    Scratch(GdnProof.Input i) {
      this(
          new float[i.kd()],
          new float[i.kd()],
          new float[i.vd()],
          new float[i.vd()],
          new float[i.vh() * i.vd()]);
    }
  }

  static float[] slice(float[] values, int row, int width) {
    return Arrays.copyOfRange(values, row * width, (row + 1) * width);
  }

  static void apply(
      String arm,
      GdnProof.Input i,
      int[] slots,
      float[] state,
      float[] out,
      Scratch s,
      NativeKernelLibrary library) {
    int stateSize = i.vh() * i.kd() * i.vd();
    switch (arm) {
      case "java" -> {
        // Match the current Qwen grouped fallback, including its row copies.
        for (int row = 0; row < i.tokens(); row++) {
          int slot = slots == null ? row : slots[row];
          GatedDeltaNetRecurrence.forwardSlotInPlace(
              slice(i.q(), row, i.kh() * i.kd()),
              slice(i.k(), row, i.kh() * i.kd()),
              slice(i.v(), row, i.vh() * i.vd()),
              slice(i.g(), row, i.vh()),
              slice(i.b(), row, i.vh()),
              state,
              slot * stateSize,
              s.rowOut(),
              s.q(),
              s.k(),
              s.memory(),
              s.delta(),
              i.kh(),
              i.vh(),
              i.kd(),
              i.vd());
          System.arraycopy(s.rowOut(), 0, out, row * i.vh() * i.vd(), s.rowOut().length);
        }
      }
      case "java-tiled-stage" ->
          GgufStagePlan.of(
                  GgufStagePlan.stage(
                      i.tokens() * i.vh(),
                      (begin, end) -> {
                        for (int unit = begin; unit < end; ) {
                          int row = unit / i.vh(), first = unit % i.vh();
                          int limit = Math.min(end - row * i.vh(), i.vh());
                          int slot = slots == null ? row : slots[row];
                          SlotTiledRecurrence.apply(
                              i, state, out, first, limit, row, row + 1, slot * stateSize);
                          unit = row * i.vh() + limit;
                        }
                      }))
              .execute();
      case "native-aligned-parallel" ->
          library.groupedGatedDeltaNetF32(
              i.q(),
              i.k(),
              i.v(),
              i.g(),
              i.b(),
              state,
              out,
              slots,
              i.tokens(),
              state.length / stateSize,
              i.kh(),
              i.vh(),
              i.kd(),
              i.vd());
      default -> throw new IllegalArgumentException(arm);
    }
  }

  public static void main(String[] args) throws Exception {
    int checked = 0;
    try (var library = NativeKernelLibrary.open(Path.of(args[0]))) {
      for (int d : new int[] {7, 128, 256}) {
        for (int branches : new int[] {1, 3, 8}) {
          int slotCount = 2 * branches + 1, size = 8 * d * d;
          float[] reference = GdnProof.random(new SplittableRandom(171), slotCount * size, .01f);
          float[] candidate = reference.clone(), nativeState = reference.clone();
          for (int step = 0; step < 18; step++) {
            var active = new ArrayList<Integer>();
            for (int branch = 0; branch < branches; branch++)
              if (step < new int[] {18, 1, 7, 8}[branch % 4])
                active.add(2 * (branches - branch) - 1);
            int[] slots = active.stream().mapToInt(Integer::intValue).toArray();
            var input = GdnProof.synthetic(slots.length, d, true);
            float[] expected = new float[input.v().length];
            var scratch = new Scratch(input);
            apply("java", input, slots, reference, expected, scratch, library);
            for (String arm : new String[] {"java-tiled-stage", "native-aligned-parallel"}) {
              float[] state = arm.startsWith("java") ? candidate : nativeState;
              float[] actual = new float[expected.length];
              apply(arm, input, slots, state, actual, scratch, library);
              String outputDifference = GdnProof.diff(expected, actual);
              String stateDifference = GdnProof.diff(reference, state);
              System.out.println(
                  "group\t"
                      + d
                      + "\t"
                      + branches
                      + "\t"
                      + step
                      + "\t"
                      + slots.length
                      + "\t"
                      + arm
                      + "\t"
                      + outputDifference
                      + "\t"
                      + stateDifference);
              if (!Arrays.equals(expected, actual) || !Arrays.equals(reference, state))
                throw new AssertionError("grouped arithmetic/state changed: " + arm);
              checked++;
            }
          }
        }
      }
    }
    System.out.println(
        "PASS " + checked + " ragged/scattered-state comparisons, including inactive slots");
  }
}

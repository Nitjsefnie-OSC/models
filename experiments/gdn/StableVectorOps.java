package com.integrallis.models.backend.purejava.qwen35;

import java.util.concurrent.atomic.LongAdder;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;

/** Diagnostic control only: deterministic per-lane transcendental operations. */
public final class StableVectorOps {
  static final LongAdder calls = new LongAdder();

  public static FloatVector lanewise(FloatVector input, VectorOperators.Unary operator) {
    if (operator != VectorOperators.EXP && operator != VectorOperators.LOG)
      throw new AssertionError("unexpected diagnostic operation: " + operator);
    calls.increment();
    float[] values = input.toArray();
    for (int n = 0; n < values.length; n++)
      values[n] =
          (float)
              (operator == VectorOperators.EXP
                  ? StrictMath.exp(values[n])
                  : StrictMath.log(values[n]));
    return FloatVector.fromArray(input.species(), values, 0);
  }
}

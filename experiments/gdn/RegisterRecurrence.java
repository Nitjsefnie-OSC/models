package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.vectors.core.MathUtil;
import com.integrallis.vectors.core.VectorRuntimeCapabilities;
import com.integrallis.vectors.core.VectorUtil;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/** Exact per-column recurrence: keep memory/readout in SIMD registers across state rows. */
final class RegisterRecurrence {
  private static final VectorRuntimeCapabilities CAPS = VectorUtil.runtimeCapabilities();
  private static final VectorSpecies<Float> SPECIES =
      VectorSpecies.of(
          float.class, VectorShape.forBitSize(CAPS.vectorApi() ? CAPS.activeVectorBits() : 128));

  private static FloatVector fma(FloatVector a, float b, FloatVector c) {
    var scale = FloatVector.broadcast(SPECIES, b);
    return CAPS.fastVectorFma() ? a.fma(scale, c) : a.mul(scale).add(c);
  }

  static void apply(GdnProof.Input i, float[] state, float[] out, int firstHead, int endHead) {
    int kd = i.kd(), vd = i.vd();
    float[][] scratch =
        GdnProof.scratch
            .get()
            .computeIfAbsent(
                kd * 1024 + vd,
                unused ->
                    new float[][] {new float[kd], new float[kd], new float[vd], new float[vd]});
    float[] q = scratch[0], k = scratch[1];
    float queryScale = (float) (1.0 / Math.sqrt(kd));
    int vectorEnd = CAPS.vectorApi() ? SPECIES.loopBound(vd) : 0;
    for (int h = firstHead; h < endHead; h++)
      for (int t = 0; t < i.tokens(); t++) {
        int qAt = (t * i.kh() + h % i.kh()) * kd,
            th = t * i.vh() + h,
            at = th * vd,
            stateAt = h * kd * vd;
        JavaChunked.norm(i.q(), qAt, q, 0, kd, queryScale);
        JavaChunked.norm(i.k(), qAt, k, 0, kd, 1);
        float decay = (float) Math.exp(i.g()[th]);
        for (int col = 0; col < vectorEnd; col += SPECIES.length()) {
          var memory = FloatVector.zero(SPECIES);
          for (int row = 0; row < kd; row++) {
            var decayed =
                FloatVector.fromArray(SPECIES, state, stateAt + row * vd + col).mul(decay);
            memory = fma(decayed, k[row], memory);
          }
          var delta = FloatVector.fromArray(SPECIES, i.v(), at + col).sub(memory).mul(i.b()[th]);
          var result = FloatVector.zero(SPECIES);
          for (int row = 0; row < kd; row++) {
            int cell = stateAt + row * vd + col;
            var decayed = FloatVector.fromArray(SPECIES, state, cell).mul(decay);
            var updated = fma(delta, k[row], decayed);
            updated.intoArray(state, cell);
            result = fma(updated, q[row], result);
          }
          result.intoArray(out, at + col);
        }
        for (int col = vectorEnd; col < vd; col++) {
          float memory = 0;
          for (int row = 0; row < kd; row++)
            memory = MathUtil.fma(state[stateAt + row * vd + col] * decay, k[row], memory);
          float delta = (i.v()[at + col] - memory) * i.b()[th], result = 0;
          for (int row = 0; row < kd; row++) {
            int cell = stateAt + row * vd + col;
            float updated = MathUtil.fma(delta, k[row], state[cell] * decay);
            state[cell] = updated;
            result = MathUtil.fma(updated, q[row], result);
          }
          out[at + col] = result;
        }
      }
  }
}

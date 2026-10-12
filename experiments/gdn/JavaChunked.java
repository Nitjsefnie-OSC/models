package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.vectors.core.VectorUtil;
import java.util.Arrays;

/** Translation of the retained Rust scan; algorithm experiment, not a production candidate yet. */
final class JavaChunked {
  private JavaChunked() {}

  static void add(float[] out, int oi, float[] in, int ii, int length, float scale) {
    VectorUtil.addScaledInPlace(out, oi, in, ii, length, scale);
  }

  static void norm(float[] from, int at, float[] to, int dest, int d, float scale) {
    float sum = 0;
    for (int n = 0; n < d; n++) {
      float x = from[at + n];
      sum += x * x;
    }
    float inv = (float) (1.0 / Math.sqrt(sum + 1e-6f));
    for (int n = 0; n < d; n++) to[dest + n] = (from[at + n] * inv) * scale;
  }

  static float dot(float[] a, int ai, float[] b, int bi, int length) {
    float sum = 0;
    for (int n = 0; n < length; n++) sum = Math.fma(a[ai + n], b[bi + n], sum);
    return sum;
  }

  static void apply(GdnProof.Input i, float[] state, float[] output) {
    int kd = i.kd(), vd = i.vd(), chunk = Math.min(64, i.tokens());
    float scale = (float) (1.0 / Math.sqrt(kd));
    float[] k = new float[chunk * kd],
        q = new float[chunk * kd],
        dk = new float[chunk * kd],
        dq = new float[chunk * kd],
        carry = new float[chunk * kd],
        p = new float[chunk * vd],
        read = new float[chunk * vd],
        update = new float[chunk * vd],
        ik = new float[chunk * chunk],
        iq = new float[chunk * chunk],
        cum = new float[chunk];
    for (int h = 0; h < i.vh(); h++) {
      int stateAt = h * kd * vd;
      for (int base = 0; base < i.tokens(); base += chunk) {
        int span = Math.min(chunk, i.tokens() - base);
        float running = 0;
        for (int t = 0; t < span; t++) {
          int src = ((base + t) * i.kh() + h % i.kh()) * kd;
          norm(i.k(), src, k, t * kd, kd, 1);
          norm(i.q(), src, q, t * kd, kd, scale);
          running += i.g()[(base + t) * i.vh() + h];
          cum[t] = running;
        }
        float tail = cum[span - 1];
        for (int t = 0; t < span; t++) {
          float decay = (float) Math.exp(cum[t]), c = (float) Math.exp(tail - cum[t]);
          for (int n = 0; n < kd; n++) {
            dk[t * kd + n] = k[t * kd + n] * decay;
            dq[t * kd + n] = q[t * kd + n] * decay;
            carry[t * kd + n] = k[t * kd + n] * c;
          }
        }
        Arrays.fill(p, 0);
        Arrays.fill(read, 0);
        for (int block = 0; block < span; block += 8)
          for (int row = 0; row < kd; row++)
            for (int t = block; t < Math.min(span, block + 8); t++) {
              if (dk[t * kd + row] != 0)
                add(p, t * vd, state, stateAt + row * vd, vd, dk[t * kd + row]);
              if (dq[t * kd + row] != 0)
                add(read, t * vd, state, stateAt + row * vd, vd, dq[t * kd + row]);
            }
        for (int t = 0; t < span; t++)
          for (int s = 0; s <= t; s++) {
            float ratio = (float) Math.exp(cum[t] - cum[s]);
            iq[t * chunk + s] = ratio * dot(q, t * kd, k, s * kd, kd);
            if (s < t)
              ik[t * chunk + s] =
                  i.b()[(base + t) * i.vh() + h] * ratio * dot(k, t * kd, k, s * kd, kd);
          }
        for (int t = 0; t < span; t++) {
          int offset = ((base + t) * i.vh() + h) * vd;
          float beta = i.b()[(base + t) * i.vh() + h];
          for (int n = 0; n < vd; n++)
            update[t * vd + n] = (i.v()[offset + n] - p[t * vd + n]) * beta;
          for (int s = 0; s < t; s++)
            if (ik[t * chunk + s] != 0) add(update, t * vd, update, s * vd, vd, -ik[t * chunk + s]);
        }
        for (int t = 0; t < span; t++) {
          for (int s = 0; s <= t; s++)
            if (iq[t * chunk + s] != 0) add(read, t * vd, update, s * vd, vd, iq[t * chunk + s]);
          System.arraycopy(read, t * vd, output, ((base + t) * i.vh() + h) * vd, vd);
        }
        float decay = (float) Math.exp(tail);
        for (int block = 0; block < kd; block += 16) {
          int end = Math.min(kd, block + 16);
          for (int row = block; row < end; row++)
            for (int n = 0; n < vd; n++) state[stateAt + row * vd + n] *= decay;
          for (int s = 0; s < span; s++)
            for (int row = block; row < end; row++)
              if (carry[s * kd + row] != 0)
                add(state, stateAt + row * vd, update, s * vd, vd, carry[s * kd + row]);
        }
      }
    }
  }
}

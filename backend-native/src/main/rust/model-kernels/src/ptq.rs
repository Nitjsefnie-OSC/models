//! PTQ1_0 projections using Q8_0 activations, as in the publisher's CPU reference.
//! Java owns graph execution and state; this module only computes packed dot products.

use super::f16_to_f32;
#[cfg(target_arch = "aarch64")]
use std::arch::aarch64::*;
#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

const POWERS: [u32; 5] = [1, 3, 9, 27, 81];

fn trit(byte: u8, power: u32) -> i32 {
    ((((u32::from(byte) * power) & 255) * 3) >> 8) as i32 - 1
}

#[inline(always)]
fn accumulate(
    total: f32,
    sums: [i32; 4],
    scales: &[f32],
    scale_at: usize,
    weight_scale: f32,
) -> f32 {
    let mut sum = 0_f32;
    for k in 0..4 {
        sum = (sums[k] as f32).mul_add(scales[scale_at + k], sum);
    }
    sum.mul_add(weight_scale, total)
}

pub(super) fn dot_scalar(
    weights: &[u8],
    input: &[i8],
    scales: &[f32],
    batch: usize,
    row: usize,
    cols: usize,
) -> f32 {
    let mut total = 0_f32;
    for block in 0..cols / 128 {
        let base = (row * (cols / 128) + block) * 28;
        let at = batch * cols + block * 128;
        let mut sums = [0_i32; 4];
        for (n, power) in POWERS.iter().enumerate() {
            for lane in 0..16 {
                let index = n * 16 + lane;
                sums[index / 32] +=
                    trit(weights[base + lane], *power) * i32::from(input[at + index]);
            }
            for lane in 0..8 {
                let index = 80 + n * 8 + lane;
                sums[index / 32] +=
                    trit(weights[base + 16 + lane], *power) * i32::from(input[at + index]);
            }
        }
        for (n, power) in POWERS.iter().take(4).enumerate() {
            for h in 0..2 {
                sums[3] +=
                    trit(weights[base + 24 + h], *power) * i32::from(input[at + 120 + n * 2 + h]);
            }
        }
        let scale = f16_to_f32(u16::from_le_bytes([weights[base + 26], weights[base + 27]]));
        total = accumulate(total, sums, scales, batch * cols / 32 + block * 4, scale);
    }
    total
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn next_trits(packed: &mut __m256i) -> __m256i {
    let triple = _mm256_add_epi16(*packed, _mm256_add_epi16(*packed, *packed));
    *packed = _mm256_and_si256(triple, _mm256_set1_epi16(255));
    _mm256_sub_epi16(_mm256_srli_epi16(triple, 8), _mm256_set1_epi16(1))
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn sum_i32(value: __m256i) -> i32 {
    let sum = _mm_add_epi32(
        _mm256_castsi256_si128(value),
        _mm256_extracti128_si256(value, 1),
    );
    let sum = _mm_add_epi32(sum, _mm_srli_si128(sum, 8));
    _mm_cvtsi128_si32(_mm_add_epi32(sum, _mm_srli_si128(sum, 4)))
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2,fma")]
pub(super) unsafe fn dot_avx2(
    weights: &[u8],
    input: &[i8],
    scales: &[f32],
    batch: usize,
    row: usize,
    cols: usize,
) -> f32 {
    let mut total = 0_f32;
    for block in 0..cols / 128 {
        let base = (row * (cols / 128) + block) * 28;
        let at = batch * cols + block * 128;
        // SAFETY: the caller validated complete 28-byte weight blocks and Q8 activation rows.
        let (mut ab, mut c) = unsafe {
            (
                _mm256_cvtepu8_epi16(_mm_loadu_si128(weights.as_ptr().add(base).cast())),
                _mm256_cvtepu8_epi16(_mm_loadl_epi64(weights.as_ptr().add(base + 16).cast())),
            )
        };
        let mut sums = [_mm256_setzero_si256(); 4];
        for n in 0..5 {
            // SAFETY: both loads are within the activation block; AVX2 is enabled here.
            unsafe {
                let qab = next_trits(&mut ab);
                let qc = next_trits(&mut c);
                let yab =
                    _mm256_cvtepi8_epi16(_mm_loadu_si128(input.as_ptr().add(at + n * 16).cast()));
                let yc = _mm256_cvtepi8_epi16(_mm_loadl_epi64(
                    input.as_ptr().add(at + 80 + n * 8).cast(),
                ));
                sums[n / 2] = _mm256_add_epi32(sums[n / 2], _mm256_madd_epi16(qab, yab));
                sums[(80 + n * 8) / 32] =
                    _mm256_add_epi32(sums[(80 + n * 8) / 32], _mm256_madd_epi16(qc, yc));
            }
        }
        // Expand the two high bytes in interleaved order to the final eight trits.
        let high = u16::from_le_bytes([weights[base + 24], weights[base + 25]]);
        let packed = _mm_unpacklo_epi8(_mm_set1_epi16(high as i16), _mm_setzero_si128());
        let packed = _mm_and_si128(
            _mm_mullo_epi16(packed, _mm_setr_epi16(1, 1, 3, 3, 9, 9, 27, 27)),
            _mm_set1_epi16(255),
        );
        let triple = _mm_add_epi16(packed, _mm_add_epi16(packed, packed));
        let tail = _mm_sub_epi16(_mm_srli_epi16(triple, 8), _mm_set1_epi16(1));
        // SAFETY: the final eight Q8 values lie within this complete block.
        let ytail =
            unsafe { _mm_cvtepi8_epi16(_mm_loadl_epi64(input.as_ptr().add(at + 120).cast())) };
        let tail_dot = _mm_madd_epi16(tail, ytail);
        let tail_sum = _mm_add_epi32(tail_dot, _mm_srli_si128(tail_dot, 8));
        let tail_sum = _mm_cvtsi128_si32(_mm_add_epi32(tail_sum, _mm_srli_si128(tail_sum, 4)));
        let mut scalar_sums = [0; 4];
        for k in 0..4 {
            // SAFETY: AVX2 was detected before selecting this function.
            scalar_sums[k] = unsafe { sum_i32(sums[k]) };
        }
        scalar_sums[3] += tail_sum;
        let scale = f16_to_f32(u16::from_le_bytes([weights[base + 26], weights[base + 27]]));
        total = accumulate(
            total,
            scalar_sums,
            scales,
            batch * cols / 32 + block * 4,
            scale,
        );
    }
    total
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn next_trits_neon(packed: &mut uint16x8_t) -> int16x8_t {
    let triple = vaddq_u16(*packed, vaddq_u16(*packed, *packed));
    *packed = vandq_u16(triple, vdupq_n_u16(255));
    vsubq_s16(
        vreinterpretq_s16_u16(vshrq_n_u16::<8>(triple)),
        vdupq_n_s16(1),
    )
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
pub(super) unsafe fn dot_neon(
    weights: &[u8],
    input: &[i8],
    scales: &[f32],
    batch: usize,
    row: usize,
    cols: usize,
) -> f32 {
    let mut total = 0_f32;
    for block in 0..cols / 128 {
        let base = (row * (cols / 128) + block) * 28;
        let at = batch * cols + block * 128;
        // SAFETY: the caller checked complete 28-byte weight and 128-value activation blocks.
        let (mut a, mut b, mut c) = unsafe {
            let ab = vld1q_u8(weights.as_ptr().add(base));
            (
                vmovl_u8(vget_low_u8(ab)),
                vmovl_u8(vget_high_u8(ab)),
                vmovl_u8(vld1_u8(weights.as_ptr().add(base + 16))),
            )
        };
        let mut sums = [vdupq_n_s32(0); 4];
        for n in 0..5 {
            // Products fit signed 16 bits, including an activation of -128. Pairwise widening
            // adds into 32-bit sums, so no floating-point reduction order changes.
            unsafe {
                let qa = next_trits_neon(&mut a);
                let qb = next_trits_neon(&mut b);
                let qc = next_trits_neon(&mut c);
                let ab = vld1q_s8(input.as_ptr().add(at + n * 16));
                let yc = vmovl_s8(vld1_s8(input.as_ptr().add(at + 80 + n * 8)));
                sums[n / 2] = vpadalq_s16(sums[n / 2], vmulq_s16(qa, vmovl_s8(vget_low_s8(ab))));
                sums[n / 2] = vpadalq_s16(sums[n / 2], vmulq_s16(qb, vmovl_s8(vget_high_s8(ab))));
                sums[(80 + n * 8) / 32] = vpadalq_s16(sums[(80 + n * 8) / 32], vmulq_s16(qc, yc));
            }
        }
        let mut scalar_sums = sums.map(|sum| vaddvq_s32(sum));
        for (n, power) in POWERS.iter().take(4).enumerate() {
            for h in 0..2 {
                scalar_sums[3] +=
                    trit(weights[base + 24 + h], *power) * i32::from(input[at + 120 + n * 2 + h]);
            }
        }
        let scale = f16_to_f32(u16::from_le_bytes([weights[base + 26], weights[base + 27]]));
        total = accumulate(
            total,
            scalar_sums,
            scales,
            batch * cols / 32 + block * 4,
            scale,
        );
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_scalar_and_vector_q8_reductions_are_bit_identical() {
        let cols = 17408;
        let rows = 3;
        let batch = 2;
        let mut state = 143_u32;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            state
        };
        let mut weights: Vec<u8> = (0..rows * cols / 128 * 28).map(|_| next() as u8).collect();
        for block in 0..weights.len() / 28 {
            let scale = [0_u16, 1, 0x3000, 0x3c00, 0xbc00, 0x1400][block % 6];
            weights[block * 28 + 26..block * 28 + 28].copy_from_slice(&scale.to_le_bytes());
        }
        // Includes -128 so signed multiplication cannot rely on an overflowing i8 negation.
        let input: Vec<i8> = (0..batch * cols).map(|_| next() as i8).collect();
        let scales: Vec<f32> = (0..batch * cols / 32)
            .map(|i| [0.0, 0.001, 0.01, 1.0][i % 4])
            .collect();
        for b in 0..batch {
            for row in 0..rows {
                let scalar = dot_scalar(&weights, &input, &scales, b, row, cols);
                assert!(scalar.is_finite());
                #[cfg(target_arch = "aarch64")]
                if std::arch::is_aarch64_feature_detected!("neon") {
                    // SAFETY: NEON and the complete input shapes were checked above.
                    let vector = unsafe { dot_neon(&weights, &input, &scales, b, row, cols) };
                    assert_eq!(scalar.to_bits(), vector.to_bits());
                }
                #[cfg(target_arch = "x86_64")]
                if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("fma") {
                    // SAFETY: features and complete input shapes checked above.
                    let vector = unsafe { dot_avx2(&weights, &input, &scales, b, row, cols) };
                    assert_eq!(scalar.to_bits(), vector.to_bits());
                }
            }
        }
    }
}

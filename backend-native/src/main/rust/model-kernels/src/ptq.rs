//! PTQ1_0 projections with the Java float-activation contract. No graph or state ownership.
//!
//! Packing is Prism's 24 base-3 bytes, two high bytes and a little-endian half scale.
//! The eight-lane reduction and tail FMAs deliberately match TernaryDequantizer exactly.

use super::f16_to_f32;
#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

const POWERS: [u32; 5] = [1, 3, 9, 27, 81];

fn trit(byte: u8, power: u32) -> f32 {
    ((((u32::from(byte) * power) & 255) * 3) >> 8) as f32 - 1.0
}

// Keep the tail inside the caller's target-feature context. An out-of-line baseline
// helper lowers each mul_add to a libc fmaf call even when its caller has FMA.
#[inline(always)]
fn finish_block(weights: &[u8], base: usize, input: &[f32], at: usize, lanes: [f32; 8]) -> f32 {
    let even = (lanes[4] + lanes[0]) + (lanes[6] + lanes[2]);
    let odd = (lanes[5] + lanes[1]) + (lanes[7] + lanes[3]);
    let mut sum = even + odd;
    for (n, power) in POWERS.iter().take(4).enumerate() {
        for h in 0..2 {
            sum = trit(weights[base + 24 + h], *power).mul_add(input[at + 120 + n * 2 + h], sum);
        }
    }
    sum
}

pub(super) fn dot_scalar(
    weights: &[u8],
    input: &[f32],
    batch: usize,
    row: usize,
    cols: usize,
) -> f32 {
    let mut total = 0_f32;
    for block in 0..cols / 128 {
        let base = (row * (cols / 128) + block) * 28;
        let at = batch * cols + block * 128;
        let mut lanes = [0_f32; 8];
        for (n, power) in POWERS.iter().enumerate() {
            for (lane, sum) in lanes.iter_mut().enumerate() {
                *sum = trit(weights[base + lane], *power).mul_add(input[at + n * 16 + lane], *sum);
                *sum = trit(weights[base + 8 + lane], *power)
                    .mul_add(input[at + n * 16 + 8 + lane], *sum);
                *sum = trit(weights[base + 16 + lane], *power)
                    .mul_add(input[at + 80 + n * 8 + lane], *sum);
            }
        }
        let sum = finish_block(weights, base, input, at, lanes);
        let scale = f16_to_f32(u16::from_le_bytes([weights[base + 26], weights[base + 27]]));
        total = sum.mul_add(scale, total);
    }
    total
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn trits(bytes: __m256i, power: u32) -> __m256 {
    let packed = _mm256_and_si256(
        _mm256_mullo_epi32(bytes, _mm256_set1_epi32(power as i32)),
        _mm256_set1_epi32(255),
    );
    let high = _mm256_srli_epi32(_mm256_mullo_epi32(packed, _mm256_set1_epi32(3)), 8);
    _mm256_cvtepi32_ps(_mm256_sub_epi32(high, _mm256_set1_epi32(1)))
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2,fma")]
pub(super) unsafe fn dot_avx2(
    weights: &[u8],
    input: &[f32],
    batch: usize,
    row: usize,
    cols: usize,
) -> f32 {
    let mut total = 0_f32;
    for block in 0..cols / 128 {
        let base = (row * (cols / 128) + block) * 28;
        let at = batch * cols + block * 128;
        // SAFETY: the caller validated complete 28-byte blocks and complete activation rows.
        let (a, b, c) = unsafe {
            (
                _mm256_cvtepu8_epi32(_mm_loadl_epi64(weights.as_ptr().add(base).cast())),
                _mm256_cvtepu8_epi32(_mm_loadl_epi64(weights.as_ptr().add(base + 8).cast())),
                _mm256_cvtepu8_epi32(_mm_loadl_epi64(weights.as_ptr().add(base + 16).cast())),
            )
        };
        let mut sum = _mm256_setzero_ps();
        for (n, power) in POWERS.iter().enumerate() {
            // SAFETY: AVX2 is enabled here; all three eight-float groups are inside the row.
            unsafe {
                sum = _mm256_fmadd_ps(
                    trits(a, *power),
                    _mm256_loadu_ps(input.as_ptr().add(at + n * 16)),
                    sum,
                );
                sum = _mm256_fmadd_ps(
                    trits(b, *power),
                    _mm256_loadu_ps(input.as_ptr().add(at + n * 16 + 8)),
                    sum,
                );
                sum = _mm256_fmadd_ps(
                    trits(c, *power),
                    _mm256_loadu_ps(input.as_ptr().add(at + 80 + n * 8)),
                    sum,
                );
            }
        }
        let mut lanes = [0_f32; 8];
        // SAFETY: the destination holds exactly eight floats.
        unsafe { _mm256_storeu_ps(lanes.as_mut_ptr(), sum) };
        let sum = finish_block(weights, base, input, at, lanes);
        let scale = f16_to_f32(u16::from_le_bytes([weights[base + 26], weights[base + 27]]));
        total = sum.mul_add(scale, total);
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_scalar_and_vector_reductions_are_bit_identical() {
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
        let input: Vec<f32> = (0..batch * cols)
            .map(|_| (next() % 65536) as f32 / 32768.0 - 1.0)
            .collect();
        for b in 0..batch {
            for row in 0..rows {
                let scalar = dot_scalar(&weights, &input, b, row, cols);
                assert!(scalar.is_finite());
                #[cfg(target_arch = "x86_64")]
                if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("fma") {
                    // SAFETY: features checked above, complete buffers and dimensions supplied.
                    let vector = unsafe { dot_avx2(&weights, &input, b, row, cols) };
                    assert_eq!(scalar.to_bits(), vector.to_bits());
                }
            }
        }
    }
}

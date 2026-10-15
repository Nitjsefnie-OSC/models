//! Capability gate for the native float SwiGLU kernel.
//!
//! The kernel itself is `sleef_activations::swi`, the aarch64 SLEEF 3.6.1 port attributed in that
//! module. This file only decides where it is allowed to run.
//!
//! It is enabled only where the Java side runs the matching float polynomial. Vectors 0.1.30 routes
//! activations to that polynomial when USE_PUBLISHED_ARM_ACTIVATIONS holds -- aarch64 with a
//! 128-bit float species; every other configuration keeps the released vector EXP intrinsic, whose
//! rounding differs by up to 1 ULP. Enabling the kernel there makes the native and Java paths
//! disagree, which SwiGluNativeKernelTest observed at indices 3, 5 and 7.
//!
//! So x86_64 declines and the caller keeps the Java route. That narrows where the kernel runs
//! rather than regressing anything: it is contained in no release tag (v0.3.58 does not have it),
//! so the Java route is exactly what x86 ran before.

pub(super) fn supported() -> bool {
    #[cfg(target_arch = "aarch64")]
    {
        super::supports_f32_fma_simd()
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        false
    }
}

/// Caller validates shape, features, numeric domain and live buffers without forward output overlap.
/// Only reached on aarch64: the exported entry point declines unless `supported()` holds, and
/// that is a compile-time `false` everywhere else.
pub(super) unsafe fn apply(out: *mut f32, gate: *const f32, up: *const f32, len: usize) {
    #[cfg(target_arch = "aarch64")]
    // SAFETY: validated by the sole exported entry point.
    unsafe {
        super::sleef_activations::swi(out, gate, up, len)
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        let _ = (out, gate, up, len);
        unreachable!("the exported entry point declines SwiGLU unless supported() holds")
    }
}

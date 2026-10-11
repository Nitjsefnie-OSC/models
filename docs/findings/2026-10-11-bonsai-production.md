# Bonsai production qualification and sequential intake

User mandate: finish production-quality Bonsai first, then process the outstanding
model queue one at a time. A passing build is not completion of qualification.

## Bonsai execution plan

1. Freeze the exact PTQ1_0 artifact and publisher reference already recorded in
   `2026-10-11-model-onboarding.md`; retain all earlier results unchanged.
2. Measure the released runtime and pinned publisher CPU reference on one isolated
   host. Record complete commands, hashes, process memory, prompts, and raw outputs.
   Profile the Java arm before choosing an optimization.
3. Repair runtime defects and measured bottlenecks with Java correctness as the
   reference. Require real-weight parity, session isolation, kernel checks, and
   same-host before/after measurements. No unmeasured optimization ships.
4. Resolve comparator admission explicitly: the current v6 policy requires Ollama
   or Transformers, neither of which presently executes this exact packed artifact.
   The publisher's fork must retain its real identity. Any additional reference
   policy must be separately versioned, justify the reference's provenance, retain
   the 0.80 throughput / 1.5 latency thresholds and all correctness gates, and leave
   historical qualifications unchanged. Collect measurements before deciding it.
5. Complete the default smoke and controlled paired RAG qualification, plus held-out
   public task evidence from more than one dataset. Record model contribution,
   failures, truncation, memory and limitations separately from pipeline fallback.
6. Release any runtime repair, repeat with downloaded Central runtime artifacts,
   then follow ModelJars' marker verification, Central publication and site process.
   Do not claim qualification or publication before its gate completes.

## First isolated experiment

- Provider: AWS us-east-1, one m6a.4xlarge, 16 vCPU / 64 GiB, 100 GiB gp3.
- Duration: at most four hours from boot, with automatic instance termination.
- Cost ceiling: USD 5 for this experiment, including disk; query live price before launch.
- Purpose: baseline CPU profiling and exact-artifact publisher comparison.
- Stop conditions: artifact mismatch, setup failure, invalid output, or deadline.
- Evidence: `benchmark-results/2026-10-11-bonsai-production/` and immutable S3 prefix.
- No other account resource may be changed; inventory found one unrelated stopped
  `ribbon-catches-bloom` instance, which is left untouched.

## Sequential queue after Bonsai

Process one target at a time; retain a concrete result and next action for every
artifact. Within a family, proceed smallest-first. Existing successful qualifications
are retained; failed tool gates are not reclassified as untested.

1. Whisper tiny, then base and small: finish frontend/encoder/cross-attention and ASR gate.
2. Qwen3-VL 2B: verified projector, vision tower, mRoPE/deepstack and vision gate.
3. OCR: LightOnOCR-3, GLM-OCR, Surya OCR 2, smallest supported artifact first.
4. Safety: Granite Guardian 3.0 2B, Llama Guard 3 1B, ShieldGemma 2B; classification gate.
5. Speech: Qwen3-TTS 0.6B CustomVoice, dots.tts-mf; family-specific voice gates.
6. Text intake: LFM2-1.2B-RAG, S1-mini, Phi-4-mini, Gemma 4 E4B, then remaining
   DeepSeek/Qwen coder, Fin-R1, SQLCoder, Gemma 3n and KAT-Coder candidates.
7. Tool intake: FunctionGemma 270M, xLAM 1B, Granite 3.3 2B; revisit rejected
   Qwen3 0.6B, SmolLM3, MiniCPM5, Llama 3.2 3B and Hammer only through a declared
   new experiment, preserving their existing negative evidence.

Sources: ModelJars `docs/MODALITY-ROADMAP.md`, `docs/model-candidates-20260803.md`,
Models `docs/proposals/model-intake-formats-and-kv-research.md`, and the workspace's
`tool-calling-small-model-candidates-2026-09-02.md`.

Status: native Q8 and Java parity checks passed; paired RAG and public QA pending.
Nothing newly qualified or published by this plan.

## Publisher-reference admission (new policy, historical v6 unchanged)

The proposed `production-rag-publisher-reference-v1` path is opt-in through
`--publisher-reference prism-bonsai2-ptq1_0`. It admits only the exact recorded
Bonsai artifact and the pinned `PrismML-Eng/llama.cpp` source revision. The backend
remains named `llama.cpp`; it is never called Ollama. Unknown references, other
artifacts, unspecified revisions, different hosts or workloads are rejected.
Quality, model contribution, 0.80 decode and 1.5 end-to-end ratio gates remain.
The existing v6 path is unchanged and still cannot qualify against llama.cpp alone.

Authority is the publisher's own format implementation, linked by the exact
[model card](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/README.md).
It is required for PTQ1_0 plus the declared Hadamard transform; stock comparator
support is not assumed. Admission does not assert the model passes any gate.

## Numerical and performance experiments

Released float-activation baseline: 1.364 median decode tokens/second on the
isolated m6a host. First Rust translation: 1.260; rejected. Inlining the tail to
avoid libc `fmaf` calls: 1.734. Independent row pairs: 1.610; rejected. A compact
trit lookup: 1.440; rejected. Sixteen-bit trit extraction plus batched transformed
prompts: 1.844 decode and 3.173 short-prompt prefill tokens/second. The sequential
prefill ablation gave 1.953 prefill tokens/second. Eight pinned physical workers
were slower than sixteen logical workers. Splitting ternary multiply/add also
regressed, to 1.757 decode tokens/second. Every raw report is retained.

The publisher uses Q8_0 activations. This is now a separate numerical-contract
experiment, not a claim of equivalence to the previous float-activation route.
A scalar Java oracle independently quantizes activations, expands the packed trits,
and folds integer block products. Rust scalar/SIMD and public FFI tests require
bit equality to that oracle. The activation scratch allocator now sizes scales by
activation block width, independently of weight block width (32 versus 128).
No Q8 default is released without real-weight checks and paired qualification.

The longer sequential/batched full-logit test exposed a difference missed by the
short oracle continuation. Its original exact-array assertion spent minutes in
AssertJ's quadratic difference formatting; that process was stopped after retaining
a thread dump. The test now reports the maximum absolute and scaled error, requires
identical greedy continuation, and sets a pre-measurement scaled-logit bound of
2e-4 (`abs(actual-expected)/(1+abs(expected))`). This is a new batch-contract check,
not a statement that the two graph paths are bit-identical. If it fails, investigate
and fix the route; do not silently widen the threshold.

The 200-case public QA window and its descriptive regression rule are frozen in
`benchmark-results/2026-10-11-bonsai-production/public-qa-protocol.md`. All 200 prompts
fit context 2048 without truncation in tokenization-only validation. No model quality
result has yet been inferred from that validation.

The Q8 native whole-model experiment measured 2.445 median decode tokens/second
against 2.625 in the publisher reference on the same m6a host/context/workload
(0.931 ratio). Median short-prompt prefill was 2.695 versus 3.019. All three trials
succeeded. This is a performance experiment, not RAG qualification.

The Q8 sequential/batched real-weight logits were exactly equal at all three
continuation steps. The test now requires zero maximum absolute error using a linear
scan, avoiding the earlier assertion library's pathological array diff. The earlier
float experiment's tolerance is not the shipped Q8 contract. Java PTQ projections
now use the same Q8 activation contract; randomized independent-oracle tests check
both production Java and Rust outputs bit for bit across rows, batches and workers.
The explicit float dot remains available for accuracy ablations, not backend fallback.
Backend diagnostics record the PTQ contract and the native plan advances to v14.

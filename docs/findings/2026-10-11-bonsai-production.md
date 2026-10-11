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

Status: Bonsai baseline investigation active; nothing newly qualified by this plan.

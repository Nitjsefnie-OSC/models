# Model qualification intake status

Snapshot of the exact candidate names in the intake proposal and subsequent user requests,
checked against the six ModelJars qualification manifests on 2026-10-12. A catalog listing
is not a passing qualification. A passing historical record applies to its artifact, workload,
runtime revision, and host; this inventory does not renew it on today's candidate runtime.

## Active priorities

- **Bonsai 2:** all eight shards of the frozen candidate's 200-case public QA comparison are
  launched. Real numerical fixtures pass on x86 and ARM. Whole-model allocation regressions
  remain release blockers; no production qualification or marker publication is claimed.
- **EmbeddingGemma 2:** released-runtime text embedding equivalence passes eight independent
  probes, minimum cosine 0.9998087, with 768-dimensional outputs. This does not qualify image,
  audio, video, or retrieval task quality.
- **MobileMoE:** its authenticated weights are installed. The independent publisher-reference
  CI now executes all seven fixture checks with no skips, and the released-runtime RAG smoke
  passes. These checks do not renew every historical workload qualification.
- **Qwen BF16:** real model repeat/reset/batch parity passes on the supported test hosts.
  Performance and resource evidence is being completed for the corrected runtime.

## Catalog evidence

The adjacent JSON preserves the complete matched entries, exact artifact IDs, report paths,
recorded runtime versions, outcomes, and manifest SHA-256 values. Multiple formats of a family
must qualify separately. `general` below means the recorded general RAG workload; it does not
imply clinical, financial, coding, tool, or multimodal qualification.

| Requested candidate | Current manifest evidence |
| --- | --- |
| Bonsai 2 27B PTQ1_0 | Candidate only; no passing qualification record |
| EmbeddingGemma 2 Q8_0 | ggml_org_embeddinggemma_2_gguf_q8_0: oracle-equivalence-v1 |
| Qwen3 8B Q4_K_M | qwen3_8b_q4_k_m: general; qwen3_8b_q4_k_m: tool |
| SmolLM3 3B Q4_K_M | smollm3_3b_q4_k_m: general; failed/unqualified record also retained for smollm3_3b_q4_k_m (tool-qualifications.json) |
| LFM2-1.2B-RAG | No catalog entry or passing qualification record |
| S1-mini | No catalog entry or passing qualification record |
| Phi-4-mini-instruct | Candidate only; no passing qualification record |
| Granite 4.1 8B | granite_4_1_8b_q4_k_m: general |
| Gemma 4 E4B IT | Candidate only; no passing qualification record |
| EuroLLM 1.7B | eurollm_1_7b_instruct_q4_k_m: multilingual |
| DeepSeek-R1-Distill-Qwen-7B | Candidate only; no passing qualification record |
| Llama 3.1 8B Instruct | llama_3_1_8b_instruct_q4_k_m: general |
| Ministral-3-3B-Instruct-2512 | ministral_3_3b_instruct_2512_q4_k_m: general |
| Gemma-3n-E2B-IT | Candidate only; no passing qualification record |
| Fin-R1 7B | Candidate only; no passing qualification record |
| HuatuoGPT-o1 7B | huatuogpt_o1_7b_q4_k_m: general |
| SQLCoder-7B-2 | Candidate only; no passing qualification record |
| Qwen2.5-Coder 3B / 7B | Candidate only; no passing qualification record |
| DeepSeek-Coder 6.7B | Candidate only; no passing qualification record |
| TinyLlama | tinyllama_1_1b_chat_v1_0_q4_0: general |
| SmolLM2 360M | smollm2_360m_instruct_q8_0: general |
| MiniCPM5 1B | minicpm5_1b_q4_k_m: coding; failed/unqualified record also retained for minicpm5_1b_q4_k_m (tool-qualifications.json) |
| MobileMoE S QAT int4-g32 | facebook_mobilemoe_s_qat_int4_g32: general |
| Qwen3-TTS 0.6B CustomVoice | Candidate only; no passing qualification record |
| dots.tts-mf | Candidate only; no passing qualification record |
| KAT-Coder V2.5 Dev | Candidate only; no passing qualification record |
| LightOnOCR-3 | No catalog entry or passing qualification record |
| Whisper | No catalog entry or passing qualification record |

## Work that remains explicit

S1-mini and a Qwen3 base plus its activated adapter are separate products: a base-model result
cannot qualify the composition or transcript normalization. The existing Qwen3 1.7B activated
tool adapter failed its general tool-quality screen and is not a substitute.

LightOnOCR-3 is included in the modality roadmap, but has no qualified catalog artifact.
Whisper has a Java mel frontend and independent tensor-oracle tests; encoder/decoder execution,
transcription correctness and WER qualification are not complete. Qwen3-TTS and dots.tts require
family-specific execution and their codec/vocoder components. Existing speech models do not
qualify these requested families.

Dolphin is a family name rather than a pinned requested artifact; provenance and workload must
be fixed before a meaningful qualification. Desert Ant was documented as a product-design and
research reference with redistribution restrictions, not a qualified ModelJar. Its narrow-task
ideas remain separate implementation work.

The immediate queue remains Bonsai, then unresolved candidates in the proposal's priority
order. Existing qualifying records should be reused only for their documented scope, and fresh
runtime regressions must be fixed before further markers or library releases.

Sources: [intake proposal](../proposals/model-intake-formats-and-kv-research.md),
[manifest snapshot](qualification-intake-2026-10-12.json), and the ModelJars modality roadmap.

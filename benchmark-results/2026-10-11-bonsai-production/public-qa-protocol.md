# Frozen public QA protocol

Frozen before observing any model output on these cases. This is additional task and
regression evidence; it does not replace the production RAG gate or manufacture an
Ollama comparison.

- 100 SQuAD 2.0 development questions, balanced answerable/unanswerable.
- 100 BoolQ development questions, balanced yes/no.
- Selection is deterministic SHA-256 ordering with the seed and source hashes in
  `public-qa-cases.json`. No selection by model output or passage length. Every
  selected case remains in the denominator, including context and runtime failures.
- Exact artifact: Bonsai PTQ1_0, SHA-256
  `53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3`.
- Compare Models and the pinned publisher reference on the same CPU host. Raw
  ChatML non-thinking prompts are identical and hashed per case. Greedy decoding,
  temperature 0, top-k 1, top-p 1, seed 42, repetition penalty 1, context 2048.
- Output caps: 64 SQuAD tokens and 8 BoolQ tokens. Record exact stop reasons,
  truncations and failures. No prompt truncation or silent dropped examples.
- Report SQuAD normalized exact match/token F1 and unanswerable accuracy; BoolQ
  yes/no accuracy. SQuAD normalization uses lowercase, ASCII punctuation removal,
  English article removal and whitespace normalization. Only an exact normalized
  `UNANSWERABLE` maps to an empty SQuAD prediction. BoolQ accepts only an exact
  normalized `yes` or `no`; prose is an invalid answer.
- Before publication, require no runtime/context failures and no extra truncation
  in Models; investigate any deficit over three percentage points in SQuAD F1 or
  BoolQ accuracy against the reference. This is a descriptive regression rule at
  n=100, not a statistical noninferiority claim. Report paired disagreements and
  binomial uncertainty, without claiming these small windows estimate all tasks.
- The supplied passage bypasses retrieval and fallback: every answer is produced
  by the model. RAG pipeline contribution/fallback is evaluated separately. Kernel
  Java/native ablations and sequential/batched prompt measurements isolate runtime
  components independently.
- These are public development sets. We cannot establish that they were absent
  from upstream model training; do not describe them as contamination-free or use
  their scores as unseen-test leaderboard claims.

Dataset attribution and licenses: SQuAD 2.0, Rajpurkar, Jia and Liang (2018),
[CC BY-SA 4.0](https://rajpurkar.github.io/SQuAD-explorer/); BoolQ, Clark et al.
(2019), [CC BY-SA 3.0](https://github.com/google-research-datasets/boolean-questions).
The selected question/passage material in `public-qa-cases.json` retains the
respective dataset license. Selection and prompt wrapping are our modifications.
BoolQ's original Google Storage URL returned HTTP 403; the data was obtained from
the pinned `google/boolq` Hugging Face mirror instead, with its hash recorded.
Rebuild with `uv run --python 3.12 --with pyarrow==19.0.1 prepare-public-qa.py`.

## Execution partition, declared before quality outputs

The 200 cases are partitioned by their frozen manifest index into eight contiguous
25-case shards. Each shard runs both complete arms sequentially on one dedicated
m6a.4xlarge host (16 vCPU, 64 GiB). No host runs two inference processes concurrently.
All cases remain in the aggregate score; every raw row retains its global index.
The merged report retains each shard's host identity, input hashes and raw manifest.
We aggregate answer-quality scores, not timings across different hosts. Relative
performance qualification remains the separate, unpartitioned RAG run on one host.
Each worker has a 150-minute termination deadline and uploads checkpoints every
minute. AWS Pricing API queried October 11 gives USD 0.6912/host-hour; the eight-host
phase is capped at USD 15 including its gp3 volumes. This is separate from the
initial single-host USD 5 experiment. An incomplete shard cannot pass by omission.

## Prompt-tokenization correction, before the replacement run

The first fleet was stopped and its partial outputs retained under `public-qa-invalid-raw/`.
Its Java harness passed the rendered ChatML through the ordinary String API, which deliberately
treats control-token spellings as text. The publisher parsed those controls. Matching character
hashes therefore did not establish matching inputs: case 125 had 295 versus 268 prompt tokens.
No quality or relative-performance conclusion is valid from that run.

The corrected harness passes the immutable benchmark template through `ModelPrompt.control`.
All 200 complete token arrays now match a tokenizer-only executable built against the pinned
publisher source; see `public-qa-token-parity.json` and `public-qa-reference-tokens.jsonl`.
Each replacement worker repeats the token check before inference, and the publisher server's
`/tokenize` result is checked again before each completion. Both raw arms retain their token IDs.
The case selection, prompt characters, token budgets, scoring and acceptance rule are unchanged.
This repairs the API mismatch, without selecting cases or tuning prompts to observed answers.

The replacement is a separately recorded eight-host phase with the same 150-minute per-host
deadline and USD 15 ceiling. The first phase's resources were explicitly terminated before it.

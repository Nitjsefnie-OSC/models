# Candidate qualification, 2026-10-21 — two new models, two workloads, protocol frozen first

Candidates came out of the playlist intake and were triaged against their real GGUF headers before
anything was built: `model-jars/docs/CANDIDATE-QUEUE-20261021.md` holds the triage, the pinned
digests, and why everything else in that corpus was deferred.

## What is being measured

| shard | model | arch | wl | tpl | mt | gb |
| --- | --- | --- | --- | --- | --- | --- |
| 700 | `minicpm5_2b_q4_k_m` | llama | `coding` | `minicpm5-no-think` | 256 | 1.56 |
| 701 | `vibethinker_1_5b_q4_k_m` | qwen2 | `math` | `chatml-direct` | 768 | 1.12 |

One workload per shard, as the runbook requires, so a box loads one corpus and `sameWorkload()`
cannot exclude the comparator over a corpus mismatch.

`scripts/fleet/build-shards.py` **refused** to build either job from recorded evidence, because no
prior report exists for either model, and printed the precedent instead. Both jobs are therefore
marked DECLARED rather than copied, and these are the declarations, with the reason for each:

- **`minicpm5_2b_q4_k_m` → `coding` / `minicpm5-no-think`.** Its 1B sibling
  `minicpm5_1b_q4_k_m` is QUALIFIED in the catalog on exactly those settings
  (`modelAnswerRate 0.444`, `modelAnswerCorrectRate 1.000`, verdict QUALIFIED, report
  `benchmark-results/certified-20260725/rag/minicpm5-1b-q4_k_m/models-rust-ffm.json`). Same family,
  same `minicpm5-pretokenizer`, same architecture. `minicpm5-no-think` is also the only template in
  the whole catalog that has qualified anything on this family, which is why the generic `chatml`
  that `llama` has qualified 3 times with was not used.
- **`vibethinker_1_5b_q4_k_m` → `math` / `chatml-direct` / mt=768.** Its base model is
  `Qwen/Qwen2.5-Math-1.5B`, and `qwen2_5_math_1_5b_instruct_q4_k_m` is QUALIFIED on `math` with
  `chatml-direct`. The cap is 768 rather than the default 256 because the 2026-10-09 max-tokens
  sweep measured this exact family truncating 55.6%–77.8% of its answers at 256 and **0.0%** at
  768. Using 256 would re-impose a constraint already measured to bind. Both arms receive the same
  cap, so `sameWorkload()` still admits the comparator.

`build-shards.py` also offered a closer-looking precedent for VibeThinker — `king3djbl_nexus_finance_gguf_q5_k_s`,
identical dimensions, qualified on `general` with `chatml` — and it was **not** taken. Identical
dimensions mean the same base architecture, not the same specialisation, and the runbook's worked
example is precisely a math specialist failing on `general`.

## Prediction, registered before the run

Publishing the null is the rule, so the expectation goes on the record first.

- **MiniCPM5-2B on `coding`: expected QUALIFIED.** The 1B qualified at these settings and this is
  the same family, 2.5× the parameters.
- **VibeThinker-1.5B on `math`: expected FAILED_MODEL_CONTRIBUTION_GATE.** Four of the five paired
  cells in the max-tokens sweep were reasoning-tuned models that stopped truncating at 768 and
  still produced `modelAnswerRate 0.000` with `extractiveFallbackRate 0.889` — they talk past the
  grounded-RAG citation screen rather than failing to finish. VibeThinker is more aggressively
  reasoning-tuned than the Qwen2.5-Math base that only just cleared the gate at 0.333. If it
  qualifies, that is the interesting result; if it fails, the useful reading is
  `modelAnswerCorrectRate` — correct-but-under-answering is a model being cautious on a corpus it
  was not built for, and is not the same finding as a zero correct rate.

A verdict for either model requires `$id.comparator.json` **and** `$id.verdict.json`. A candidate
report alone is `NO_COMPARABLE_BASELINE` and is not a result.

## Inputs, and why each one is what it is

| input | value |
| --- | --- |
| payload | `models-rag-bench-0.5.2-candidates-20261021.tar`, sha256 `e1df929dbb531c0f387a35aa3a8ea5108e30424239238835075c1959f77868f1` |
| kernels | `backend-native-0.5.2.jar` (the released Central artifact, sha256 of its Linux `.so`: `d2052d82348394c5326c52004b7dd4f9a71011ea0c58fdebde941825e25cf06b`, abi=7) |
| backend version | `models@0.5.2+candidates-9ad3fe5e46a4` |
| worker | `payload/qual-worker-two-arm-20261021.sh`, sha256 `0c3366e5dcd53053f572d20d112e50d842e1d7890dd3ae2930221632ba5e74cb`, byte-identical to the committed `scripts/fleet/qual-worker-two-arm.sh` |
| threads | `QUAL_THREADS=16`, passed to **both** arms from one variable |

**The library is the released 0.5.2, not a dev build.** An entry's `backendVersion` is a claim a
reader can check against Maven Central, and the newest payload in the bucket before this run was
`0.3.58`.

**0.5.2 changed where the kernel comes from.** The worker's own comment says backend-native's
published JAR carries classes only and that the `.so` must come from a separate platform JAR; that
was true at 0.3.56 and is **no longer true**: the released `backend-native-0.5.2.jar` contains
`META-INF/models/native/linux-x86_64/libjmodels_kernels.so` at abi=7. So the same jar is passed as
`QUAL_KERNELS`, which keeps the worker's version guard doing its job instead of being satisfied by
a stale object under a fixed name — the exact failure that put a September kernel under a
0.3.56 library for a whole campaign.

**The payload's provenance.** Built from commit `9ad3fe5e46a4b9943e0a4df4bbdc7da94e6fe417` in the
`models-final-integration` worktree. That worktree has three uncommitted paths at build time —
`benchmark-results/README.md`, `models-bench/build.gradle.kts` and one JMH source file — and none
of them is on the measured path: `benchmark-results/` is evidence, and `models-bench` is the JMH
module, which the payload does not contain (`tar tf | grep -c models-bench` = 0). The commit is now
frozen by this payload and must not be rewritten.

## Preflight, per VPS-BENCHMARK-OPERATIONS.md

```text
Experiment:            qualification of 2 new catalog candidates, 1 model per shard
Provider / region:     AWS EC2 / us-east-1
Exact plan:            2 x m6a.4xlarge, 16 vCPU each, 160 GB gp3, terminate on shutdown
Expected hourly:       about USD 0.69 per box
Maximum authorized:    USD 6.00 total
UTC creation deadline: 2026-10-22T04:00:00Z
UTC deletion deadline: self-terminating; worker DEADLINE_SECONDS=18000, HOLD_ON_FAIL 45 min
Correctness gate:      production-rag-model-contribution-v6, two arms on one host
Performance gate:      minimumDecodeThroughputRatio 0.8, maximumEndToEndLatencyRatio 1.5
Local evidence dest:   this directory
```

Inventory read immediately before launch: **zero** EC2 instances in `running`, `pending`,
`shutting-down` or `stopping`; vCPU quota **192.0**, read from
`service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A` rather than assumed.
Two boxes take 32 of it.

## Results

Shards 700 and 701 **never ran**: both died at `cloud-final` twelve seconds after boot and idled
for forty-five minutes before being terminated. The bootstrap fetched the worker with `aws s3 cp`,
and this AMI has no AWS CLI — the worker is the thing that installs it, from the bundled installer,
because `apt-get install awscli` cannot succeed here. `get-console-output` was the only place that
said so, exactly as the runbook warns. Shards **702 and 703** are the same two jobs with the worker
fetched by `curl` from a presigned URL instead. Cost of the mistake: about USD 1.00.

### `minicpm5_2b_q4_k_m` on `coding` — FAILED_RELATIVE_GATE, and the model is not what failed

| | value | gate |
| --- | --- | --- |
| `modelAnswerRate` | **0.667** (18 of 27 attempts) | >= 0.333 **pass** |
| `modelAnswerCorrectRate` | **1.000** | >= 0.90 **pass** |
| `absoluteTier` | USABLE | — |
| `decodeThroughputRatio` vs ollama | **0.765** | >= 0.8 **fail** |
| `endToEndLatencyRatio` vs ollama | **1.590** | <= 1.5 **fail** |
| `timeToFirstTokenRatio` vs ollama | 2.297 | reported, not gated |
| verdict | **FAILED_RELATIVE_GATE** | |

**The contribution gate passed with room to spare, and better than the 1B sibling that is already
in the catalog** (0.667 against 0.444, both at a perfect `modelAnswerCorrectRate`). What failed is
ours: on 16 vCPU EPYC our decode throughput is 76.5% of ollama's on this model and our end-to-end
latency 1.59x, against floors of 0.8 and 1.5. Two arms, one host, one session; the comparator ran
on the same artifact sha.

**The prediction registered above was wrong, and wrong in an informative direction.** It said
QUALIFIED on the strength of the sibling's contribution numbers, and the contribution numbers were
indeed fine — the relative gate is what bit, and nothing in the sibling's evidence spoke to it.
Predicting on the gate you are thinking about is how you miss the one you are not.

What follows from it, in order:

1. **Re-run with `dt=8`.** This job ran `dt=pool`, which is all 16 SMT threads. The campaign's own
   decode-thread sweep (shards 40/51/52) measured **every** K-quant model faster at 8 than at 16 —
   +3% at 1.28 GB, +11% at 2.39 GB, +26% at 0.32 GB — and this artifact is 1.56 GB of K-quants.
   That is a documented per-job knob for our arm, with the declared `--threads` budget unchanged
   for both arms, not a benchmark tuned to the answer.
2. If `dt=8` does not clear 0.8, the finding is a **runtime** finding about a 2.25B K-quant model
   and belongs in the decode work, not in the catalog. The model does not get published either
   way until the gate passes on the shipped library.

### `vibethinker_1_5b_q4_k_m` on `math` — FAILED_MODEL_CONTRIBUTION_GATE, and inconclusive

| | value |
| --- | --- |
| `modelAnswerRate` | **0.000** (0 of 27 attempts) |
| `modelAnswerCorrectRate` | 0.000 |
| `truncatedAnswerRate` | **0.889** — at **mt=768** |
| `extractiveFallbackRate` | 0.889 |
| `totalOutputTokens` | **20,175** |
| `correctAnswerRate` (pipeline) | 1.000 |
| `rawCorrectAnswerRate` | 0.889 |
| retrieval: recall / MRR / factCoverage / citation recall and precision | 1.0 across the board |
| `absoluteTier` | PERFORMANCE_REDUCED |
| verdict | **FAILED_MODEL_CONTRIBUTION_GATE** |

The verdict matches the registered prediction. **The mechanism does not**, and the mechanism is
what matters. The prediction said this model would talk past the citation screen the way four of
the five cells in the October max-tokens sweep did. It did not: it **never finished an answer**.
`truncatedAnswerRate` is **0.889** at a 768-token cap — eight of nine answers cut off at three
times the cap that sweep was run to fix — and it emitted **20,175** output tokens doing it,
against the 12,036 of Fin-R1, which that sweep called "twice any other model here".

That sweep's own ordering rule settles how to read this: *any pair still truncating at the higher
cap is reported CAP STILL BINDING and inconclusive regardless of what the verdict did.* So this is
**not** evidence that VibeThinker cannot contribute on `math`. It is evidence that it does not
finish a grounded answer inside 768 tokens, and retrieval was perfect in every one of those
attempts, so nothing upstream of generation is implicated.

Two things follow, and only one of them is a measurement:

1. **A third cap would make the model's contribution measurable** — the same queue Fin-R1 sits in.
2. **Whether that is worth running is a product question, and the answer here is no.** This
   workload asks for one cited sentence out of a retrieved document. A model that spends roughly
   2,200 output tokens per attempt and still does not reach an answer is not a fit for it at any
   cap this catalog would ship, and raising the cap for every model to accommodate one is a
   declared protocol change rather than a per-model rescue. VibeThinker stays a catalog
   **candidate** with this evidence attached, and the `math` workload is not where it earns an
   entry. Its own card advertises competitive-programming and olympiad reasoning; the honest next
   question is whether this catalog should carry a workload that measures *that*, which is a gate
   nobody has written.

### Re-run at `dt=8`: `minicpm5_2b_q4_k_m` — **QUALIFIED**

Launched from the shard-702 report with `--dt-override minicpm5_2b_q4_k_m=8` and nothing else
changed — `build-shards.py` copied the workload, template, cap and backend label out of that run
rather than letting anyone retype them, and printed the prior report it copied from.

| | dt=pool (16) | dt=8 | gate |
| --- | --- | --- | --- |
| `decodeThroughputRatio` | 0.765 | **0.844** | >= 0.8 |
| `endToEndLatencyRatio` | 1.590 | **1.465** | <= 1.5 |
| `timeToFirstTokenRatio` | 2.297 | 2.349 | reported, not gated |
| our decode tok/s | 19.08 | **21.33** | — |
| ollama decode tok/s | 24.94 | 25.28 | — |
| `modelAnswerRate` | 0.667 | 0.667 | >= 0.333 |
| `modelAnswerCorrectRate` | 1.000 | 1.000 | >= 0.90 |
| verdict | FAILED_RELATIVE_GATE | **QUALIFIED** | |

**Halving the decode threads moved our decode throughput 19.08 to 21.33 tok/s, +11.8%, and that
was the whole difference between failing and qualifying.** The comparator moved 1.4% between runs,
which is the run-to-run noise on this host; our arm moved eight times that. This is the campaign's
own decode-thread finding reproducing on a model it had not been measured on: every K-quant model
in the shard-40/51/52 sweep was faster at 8 threads than at 16, by +3% to +26%, and a 1.56 GB
K-quant lands in the middle of that range.

Contribution is unchanged, as it must be — the same artifact answering the same corpus. Only our
own thread count moved, and the declared `--threads 16` budget was identical in both arms of both
runs.

**It is not published yet.** Per the runbook a newly qualified model needs a
`defaultConfigurationSmoke` on the RAG manifest: one run under the public library defaults, no
`-Dmodels.*` of any kind, `warmups == 0`, `iterations == 1`,
`native-quantized-decode == "true"`, and `correctAnswerRate == 1` with `abstentionAccuracy == 1`.
That run has not happened, and `dt=8` is a tuned decode-thread count, so the smoke has to be its
own run at defaults rather than a reinterpretation of this one.

## Cost and what it bought

Five boxes, three of them wasted on my own bootstrap mistakes: shards 700 and 701 died because the
bootstrap assumed an AWS CLI the AMI does not have, and the first 704 died because I edited a
bootstrap with `sed` and a presigned URL is full of `&`, which `sed` expands to the whole match —
it wrote a 3089-byte URL where a 367-byte one belonged. About USD 1.30 of the roughly USD 2.10
spent went on those three. The rule both break is the same one: **write the bootstrap, do not
transform it, and read the console output first when a box goes quiet.**

What the two surviving shards bought: one model qualified on measured evidence, one model's
verdict shown to be an artifact of a cap rather than a property of the model, and a reproduction
of the decode-thread finding on a new model that turned a failure into a pass.



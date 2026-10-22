# Qualifying Laya: a `modern-bert` encoder and a typed-decision gate

**Status: oracle established, gates 1 and 4a closed, the forward pass not implemented, nothing
qualified.** Read that sentence before quoting anything else here.

| gate | state |
| --- | --- |
| 1. `modern-bert` config | **closed.** `ModernBertConfig` + 12 tests, built from the real header |
| 2. weights | **closed.** `ModernBertWeights` + 7 tests against a nano artifact shaped like the real one; every tensor the file carries is claimed |
| 3. encoder forward vs llama.cpp | not started |
| 4a. parallel layout arithmetic | **closed.** `ModernBertDecisionLayout` reproduces the reference's position and option ids exactly, on all eight cases |
| 4b. permutation ablation through the model | blocked on gate 3 |
| 5. decision head vs `REFERENCE.json` | **logit-to-answer half closed**: our decoder reproduces the reference's published probabilities for all eight cases to 1e-6. The head that *produces* the logits is not implemented |
| 6. temperature resolution and clamp | **closed**, against the reference's own numbers |
| 7. policy `decision-calibration-v1` | not written |
| 8. qualify | blocked on all of the above |

Laya is `ggml-org/Laya-GGUF` (Apache-2.0, 449 MB at Q8_0, 369M parameters by its own tensors). It
is a non-autoregressive typed-decision model: give it a state and typed questions, get back a
label and a calibrated probability per question in one forward pass. That is the same shape as our
own decision work, which is why it is worth the runtime it costs.

## Why it needs new infrastructure at all

| piece | status |
| --- | --- |
| `modern-bert` architecture | **not served.** The catalog serves `bert`, `nomic-bert`, `jina-bert-v2`, `deberta-v2` |
| alternating sliding-window / global attention | new: window 128, pattern 3 |
| two rope bases | new: 160,000 global and 10,000 windowed |
| custom position ids and an option-isolating mask | new: the "parallel layout" below |
| two bias-carrying decision blocks | new block variant: plain MLP at width 4096, not the encoder's GeGLU at 2624 |
| option-marker scoring head | new: one logit read at each option's `[MASK]` position |
| a qualification policy that describes any of this | **does not exist.** `correctAnswerRate` is a grounded-RAG pipeline metric |

What is *not* new is encouraging: `BertConfig` already carries an `EncoderStyle` enum, a
bidirectional-attention assertion, a rope base and a gated-feed-forward flag for `nomic-bert`, and
`BertClassificationHead` already loads `cls.weight`, `cls.bias`, `cls.output.weight` and
`cls.output.bias` — the exact tensor names this checkpoint uses. This is an extension of a tested
encoder path, not a runtime from scratch.

## What the artifact is, read from its own header

28 encoder blocks + 2 decision blocks + heads, 201 tensors, no towers:

- encoder blocks 0–27: `attn_qkv` [1024,3072] fused, `attn_output` [1024,1024], `ffn_up`
  [1024,**5248**] and `ffn_down` [**2624**,1024] — 5248 = 2 x 2624, so the MLP is **GeGLU** —
  `ffn_norm` and (for blocks 1–27 only, **not block 0**) `attn_norm`. No biases anywhere.
- decision blocks 28–29: same attention shape **with biases**, `ffn_up` [1024,**4096**] and
  `ffn_down` [4096,1024] — a **plain MLP**, not GeGLU — and biases on every norm and projection.
  `modern-bert.decision.block_count = 2` names them.
- heads: `output_norm`, `token_embd` [1024,50368], `token_embd_norm`, `token_types`
  [1024,**3**] (one row per question type), and `cls.norm.{weight,bias}`, `cls.{weight,bias}`
  [1024,1024], `cls.output.{weight,bias}` [1024]/[1].
- `feed_forward_length` is a **per-layer array**: 2624 x 28 then 4096 x 2.
- tokenizer: `model = gpt2`, `pre = modern-bert`, 50,368 tokens, 50,009 merges, CLS 50281,
  SEP 50282, **MASK 50284** (the option marker), PAD 50283, UNK 50280, 3 token types.
- the artifact also carries the protocol: `tokenizer.chat_template.systemone` begins
  `[CLS]{{ type }} question: {{ instruct…`.

## The reference, and why there has to be one

`oracle/oracle.py` produces `oracle/REFERENCE.json` from the upstream `laya` package (0.4.13,
Apache-2.0). The runbook forbids validating a decoder only against our own scalar reference, and
for this model the usual oracle does not cover it: **llama.cpp has a `modern-bert` encoder graph
but no decision head**, and its reranker path applies the same `cls.*` tensors in a different
order — dense, GELU, norm — than the order this checkpoint trained them in. From the reference:

```python
self.scorer = nn.Sequential(nn.LayerNorm(d), nn.Linear(d, d), nn.GELU(), nn.Linear(d, 1))
```

**LayerNorm first, then dense, then GELU, then the single-logit projection.** An implementation
that followed llama.cpp's reranker order here would load every tensor, assert every shape, and
compute a different function. So: llama.cpp can serve as the oracle for the 28-block encoder half
later; the head half is checked against the reference only.

### The forward pass the reference performs

```
h = encoder(ids, mask = parallel_option_masks(...), position_ids = parallel_layout(...))
h = h + token_types[qtype]                 # qtype: choice=0, score=1, noul=2
h = decision_block_28(h); h = decision_block_29(h)      # norm_first, nhead 16, ffn 4096
m = h[markers]                             # one row per option
logits = cls.output( GELU( cls( cls.norm(m) ) ) )       # one logit per option
answer = argmax(logits / temperature[qtype, option_count])
```

### The head prompt, exactly

`[CLS] <type> question: <instructions> [SEP] [MASK] opt0 [MASK] opt1 … [SEP] <state>`

Each option is preceded by a `[MASK]`, and that `[MASK]`'s index is the option's marker. Option
text is capped at 48 tokens by the tokenizer; the whole head is capped at `head_max_len = 192`
(`modern-bert.decision.max_head_tokens` says 192 too). `noul` options are always rendered
`[false, true]` in that order; `score` options render as `level <i>: <text>`.

### The parallel layout, which is the part a reimplementation will get wrong

The reference does not lay options out sequentially. It gives every option **the same starting
position id** and a mask that stops an option attending to any other, so that reordering the
options only reorders the logits. Its own comment measures what sequential layout costs: the slot
logits of five identical options spread by 3.59. Verified present in our artifact — every case's
markers share one position id, `option_ids` is 0 for shared tokens and `s+1` for option `s`, and
the shared tail resumes after the longest option:

| case | options | position id at every marker | tail resumes | max position | tokens |
| --- | --- | --- | --- | --- | --- |
| churn_risk (noul) | 2 | 15 | 33 | 61 | 70 |
| billing_or_not (choice) | 2 | 10 | 25 | 56 | 62 |
| department (choice) | 4 | 11 | 46 | 57 | 83 |
| queue_eight (choice) | 8 | 12 | 66 | 58 | 103 |
| intent_twelve (choice) | 12 | 12 | 93 | 56 | 130 |
| urgency (score) | 3 | 10 | 26 | 52 | 63 |
| severity_five (score) | 5 | 13 | 38 | 54 | 75 |

## Two findings the oracle produced before a line of Java was written

**1. The artifact ships a temperature its own reference implementation refuses.** The GGUF
declares `decision.temperature.choice.11 = 0.100583`. The reference clamps every temperature to
[0.5, 5.0] and warns that the affected entries are uncalibrated; its comment says why plainly — a
0.1006 temperature multiplies the logits about tenfold, "a 0.24 top probability is published as
0.99, so a caller gating on confidence is told a coin flip is a certainty." Our implementation
must clamp identically, and an 11-or-more-option choice question must be reported as
**uncalibrated** rather than carrying a probability.

**2. The bucket keys in the GGUF are not the keys the reference looks up.** The header spells them
`temperature.choice.3_5` and `temperature.choice.11`; the reference builds `choice:3-5` and
`choice:11+`. The first run of our own oracle script used a hand-built key, missed every bucket,
and silently fell back to the generic per-type temperature — giving T=1.6369 for all four choice
arities instead of 1.9064 / 1.7602 / 1.0000 / 0.5000. The script now calls the reference's
`temp_bucket` rather than reimplementing it, and the artifact records the resolved key per case.

## The reference artifact

`oracle/REFERENCE.json`, from checkpoint revision `7b928d828b7b0e022f929d9bd2e44165aa270148` —
**the revision `ggml-org/Laya-GGUF` was converted from**, per its `.src_sha`
(`PRIMARY=7b928d82…`), and *not* the revision the `laya` package pins by default
(`55cf4c4e…`). Taking the package default would have produced an oracle for weights the artifact
does not contain.

Eight cases over one state, covering every type and every temperature bucket: noul (x2),
choice at 2 / 4 / 8 / 12 options, score at 3 / 5. Per case it records the token ids, the markers,
the parallel-layout position and option ids, the raw per-option logits, the resolved temperature
key and value, the scaled logits and the probabilities, plus the public API's answers over the
same inputs so the hand-driven path is pinned against what a caller gets.

The run was CPU, float32, no autocast, on an ephemeral Linux host, because torch on this
workstation caps at 2.2.2 (the last macOS x86-64 wheel) and transformers' ModernBERT needs newer.
**No timing is claimed from it** — it is a correctness artifact.

## Gates, each blocking the next

| # | work | gate |
| --- | --- | --- |
| 1 | `modern-bert` config: new `EncoderStyle`, the `modern-bert.` prefix, per-layer FFN widths, `classifier.pooling_type`, the two rope bases, the window and its pattern, the decision-block count and `max_head_tokens` | a config built from the real header reports every one of those values; a fixture missing any of them is refused rather than defaulted |
| 2 | weights: fused QKV, GeGLU encoder blocks, block 0 without `attn_norm`, bias-carrying decision blocks, the four `cls.*` tensors, `token_types` | every tensor in the artifact is claimed by the loader and none is invented; a shape mismatch names the tensor |
| 3 | encoder forward | hidden states for a pinned input match **llama.cpp**'s `modern-bert` graph tensor by tensor, via `llama-eval-callback`. A rotary fixture is at least 4 wide and the assertion is proven sensitive by flipping the base and watching it fail |
| 4 | parallel layout and option masking | **a permutation ablation**: reordering the options of a question permutes the logits and changes nothing else. A flat result fails this gate rather than passing it, because sequential layout is what it would look like |
| 5 | decision head: type embedding, 2 blocks, scorer in the reference's order | the eight cases in `REFERENCE.json` reproduce to a tolerance written down **before** the comparison runs |
| 6 | temperature resolution and the clamp | the bucket key is derived for every (type, arity) pair in the artifact, and `choice:11+` clamps to 0.5 with the entry marked uncalibrated |
| 7 | policy `decision-calibration-v1` | label accuracy, per-arity ECE and Brier, two arms on one host. `correctAnswerRate` is not reused and not reported |
| 8 | qualify | against a **released** library, with the catalog entry naming it |

## The gate that does not exist yet, and what it should measure

A typed-decision model emits a label and a probability. Accuracy alone would miss the whole point
of the model, and `correctAnswerRate` describes a grounded-RAG pipeline that is not involved here.
`decision-calibration-v1` should report, per (question type, option-count bucket):

- **label accuracy** against a committed labelled set
- **ECE** over 10 equal-width bins, and the **Brier** score, both on the temperature-scaled
  probability the model actually publishes
- **the raw and clamped temperature**, because one bucket in this artifact differs between them
- **two arms on one host**: Laya against our own decision arm on identical items

We already hold a labelled set that fits and a second arm to run against it: the SNIPS and TREC
samples in `articles/models-deep-dive/experiments/routing-accuracy` (100 items each, 7-way and
6-way intent labels — `choice:6-10` territory) and Harriet, measured on exactly those items, with
the calibration method already written and reproduced in
`articles/models-deep-dive/experiments/decision-threshold` (reliability bins, ECE, Brier, Platt
scaling fitted out-of-fold). That makes the quality gate a reuse rather than a new build — but it
is still a gate that has to be written, and no number from it exists yet.

## What is upstream's claim and not ours

The model card reports 0.766 accuracy on its four typed-decision workflows, ECE 0.246 → 0.081
after temperature fitting, and a comparison against TypeSafe Jev. **Those are vendor
self-reported numbers under a protocol they chose, and none of them has been re-run here.** The
card also states two limitations that bear on the gates above: `action.act_probability` carries no
usable signal (AUROC 0.30 against correctness, upstream issue #185 — and the GGUF conversion drops
that head entirely, so we cannot expose it anyway), and high-cardinality questions degrade badly
because options share the 192-token head budget (0.425 at 77 options, by their own measurement).


## What is implemented, and what each piece is checked against

`backend-java/.../bert/ModernBertConfig.java` — the architecture contract. A sibling of
`BertConfig` rather than another `EncoderStyle` on it, because two of that record's invariants do
not hold here: the feed-forward width is per layer (2624 x 28 then 4096 x 2) and there are two
rotary bases. Nothing is defaulted: a missing `sliding_window_pattern` would silently make every
layer windowed and still load, so it raises instead. `ModernBertConfigTest` builds it from the
header this document records and asserts the refusals as well as the reads.

It also owns the two findings above as behaviour rather than prose: `temperatureBucket` builds the
key the reference looks up (`choice:3-5`, not the header's `choice.3_5`), and
`Decision.isUncalibrated(bucket)` answers true for `choice:11+` because its declared 0.100583 had
to be clamped to 0.5.

`backend-java/.../bert/ModernBertDecisionLayout.java` — the parallel layout. Pure index
arithmetic, which is the only reason it could be verified before any weight is loaded:
`ModernBertDecisionLayoutTest` reads `modernbert/laya-reference.json` (generated from
`oracle/REFERENCE.json`) and asserts the position ids and option ids **match the reference exactly
on all eight cases**, that every option marker shares one position, and that an option is isolated
from other options but never from the state.

The fixture varies option count (2, 3, 4, 5, 8, 12) and option width. A fixture with uniform
options could not fail on the one thing this layout exists to do, which is share positions across
options of different lengths — and the derivation of `headLength` from the option ids would not be
checked by it either.

`backend-java/.../bert/ModernBertDecisionAnswer.java` — logits to a published answer. Feeding the
reference's own raw logits through it reproduces the reference's probabilities **for all eight
cases within 1e-6**, a tolerance written down before the comparison ran. One assertion covers the
three places this step has to agree: the bucket key, the clamp, and the softmax.

It also carries the clamp finding as behaviour. `uncalibrated()` is true for the twelve-option
case and false for the other seven, so a caller reads "ranked, but this probability is not one
anybody fitted" instead of a confident number. An artifact that declares no calibration at all
decodes at temperature 1.0 and is reported uncalibrated too, rather than borrowing a default.

`backend-java/.../bert/ModernBertWeights.java` — the tensor contract, and the heterogeneity is
the whole content of it. Block 0 carries no `attn_norm` (the embedding norm feeds its attention
instead), the two decision blocks carry biases on every projection and norm where the encoder
blocks carry none, the encoder's `ffn_up` is **twice** the declared intermediate because the GeGLU
packs gate and value into one tensor while the decision blocks' matches exactly, and
`token_embd_norm` has a weight and no bias unlike classic BERT's. Each of those was read off the
real file; none is in the model card.

`unclaimedTensors` is there because "every shape matched" is a weaker statement than it sounds —
a loader can satisfy every assertion and still ignore a tensor the forward pass needs, which is a
wrong answer rather than a failure. The test asserts it is empty.

The fixture is `NanoGgufModel.writeModernBertDecision`, which writes a 6-block artifact in the
real shape: block 0 without its norm, 4 encoder blocks against a window pattern of 3 so the
global/windowed alternation is observable, 2 decision blocks with biases, two feed-forward widths,
3 type-embedding rows, and F16 matrices — because the production loader refuses a projection type
the batched matmul cannot take, and an F32 fixture would have needed that guard weakened to load.
The guard stayed; the fixture changed.

### What is left, in dependency order

1. **Encoder forward**, checked against llama.cpp's `modern-bert` graph with
   `llama-eval-callback`, tensor by tensor. The rotary fixture must be at least 4 wide and the
   assertion proven sensitive by swapping the two bases.
2. **Decision head forward**: type embedding added at every position, two blocks with
   `norm_first`, then the scorer at the marker positions. Gate: the eight cases' *raw logits*,
   which `REFERENCE.json` already holds — the decoder above only proves what happens after them.
3. **Head prompt construction**, including the tokenizer and the 48-token-per-option and
   192-token-head caps. The artifact carries the template as
   `tokenizer.chat_template.systemone`, so this is transcription rather than invention; the gate
   is that the token ids for all eight cases match `REFERENCE.json` exactly.
4. **`decision-calibration-v1`**, then two arms on one host.

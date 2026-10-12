# Q5_1 dispatch and allocation follow-up

This phase measured the actual public BERT embedding and causal-model APIs on matched x86 and
ARM hosts. Models source `99fa0e23` contains the decoder singleton and narrows native Q5_1
eligibility to x86 single-token projections. Vectors source `ae337eeb` includes the activation
boundary and executor fixes; its core JAR hash is
`696c2a58843181a2b02c91043208b56c9c6ebdc976e0d3f44dc051cc1f36d555`.

The complete results reject an unconditional performance claim. They distinguish a new BERT
regression from the existing cost of selecting the Rust backend.

## Correctness and route observation

- All 82 SciFact/NFCorpus query/document embeddings match the prior BERT implementation exactly
  on both hosts.
- All 148 causal requests match across Java/native and x86/ARM: nine owned full-logit snapshots
  per request, or 1,332 vectors per configuration. The fixture is a reproducibly derived Q5_1
  conversion, not a claimed publisher-supplied Q5_1 artifact.
- The independent route probe records 1,353 native Q5_1 calls on x86, all with batch size one,
  and zero native Q5_1 calls on ARM. Merely loading a native library is not treated as execution.
- The exact frozen Vectors artifact passes the public activation API cold/hot tests, ten million
  random bit patterns, and 580,911,104 exhaustive values at each forced width 64/128/256 on each
  host. The exhaustive total is 3,485,466,624 inputs; random total is 60 million.

## Complete paired process results

Every ratio below is candidate/control; lower is better. There are ten counterbalanced process
pairs per comparison. Each BERT process has ten warm embeddings and three passes over eight
published-data inputs. Each causal process has 32 warm requests and three measured requests.
Bootstrap intervals resample whole paired processes, not individual requests.

| Comparison | Host | Latency | Steady allocation | Peak RSS | Model load |
|---|---|---:|---:|---:|---:|
| BERT candidate / prior Java | x86 | 0.7805 | 0.1230 | **1.0925** | **1.0319** |
| BERT candidate / prior Java | ARM | 0.6725 | 0.4354 | **1.6895** | 0.9981 |
| Causal hybrid / Java | x86 | 0.9105 | 0.7207 | 1.0393 | 1.1935 |
| Causal hybrid / Java | ARM | 1.0421 | 1.0168 | 1.0059 | 1.2077 |

Full 95% intervals and separate prefill/decode measures are in `paired-summary.json`.
The BERT RSS increases are significant: x86 [1.0427,1.1475], ARM [1.6109,1.8333]. Lower latency
and steady allocation do not erase this regression. A separate component comparison isolates
scalar/SIMD decoding and serial/parallel row dispatch before selecting the production route.

The causal comparison is **Rust backend versus Java backend**, not final versus published Rust.
It includes existing Rust attention and library/pool startup. In particular, ARM executes no
native Q5_1 projection, so its remaining overhead cannot be attributed to Q5_1 kernel execution.
The x86 decode improvement is 0.7374 [0.7116,0.7664]. These measurements establish neither a JVM
limitation nor a newly introduced startup regression. A prior-native-route control is required
to isolate the dispatch change's release impact.

The singleton removes one temporary decoder per row. A subsequent source inspection identified
unnecessary eager Q5_1 class initialization on unrelated tensor formats; commit `9027551b` moves
the singleton into a lazy holder without changing arithmetic. That later change is not silently
assigned to these frozen measurements.

Raw files remain in `remote-q5-final-v3` under the GDN evidence directory and at
`s3://models-qual-077051030817/results/gdn-proof-q5-final-v3/`. The SHA index, complete worker
commands, immutable payload and host receipts identify the experiment. Both instances completed
all phases and were terminated before their slots were reused for the component comparison.

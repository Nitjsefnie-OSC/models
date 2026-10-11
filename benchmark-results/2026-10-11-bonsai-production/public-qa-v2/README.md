# Complete source-runtime public QA pair

All 200 frozen cases completed on both arms, paired within each of eight m6a.4xlarge hosts.
All hosts terminated; see `termination.json`. The merged manifests retain exact source report
hashes, host identities, input token arrays, artifact identity and generation controls.

| Dataset | Models | Publisher | Cases |
| --- | --- | --- | ---: |
| SQuAD 2.0 normalized exact match | 0.77 | 0.77 | 100 |
| SQuAD 2.0 token F1 | 0.800297619 | 0.800297619 | 100 |
| SQuAD 2.0 unanswerable accuracy | 0.74 | 0.74 | 50 |
| BoolQ strict yes/no accuracy | 0.90 | 0.90 | 100 |

No execution failures or truncations occurred in either arm. The one wording difference
(case 89) is an accepted gold answer in both forms and scores identically. The frozen descriptive
regression rule passes on both datasets. `scores.json` includes binomial intervals and the
paired disagreement; `raw/` retains the complete runs, and `merged/` retains all 200 rows per arm.

The runtime source is `8b5c0b29d25bfce0e54659778e08ca65dcbd322b`, with the corrected prompt-control
harness at `50489734`. This predates the BF16 repair and is not a released-runtime qualification.
Final admission requires another complete pair with the final released runtime. These small,
public development windows do not establish absence of training contamination or general-task
performance; the uncertainty and scope limits in the frozen protocol continue to apply.

# Bonsai public QA: completed corrected-runtime comparison

[Run 38345112860](https://github.com/integrallis/models/actions/runs/38345112860)
completed all 200 frozen paired cases across 20 hosts. The local independent analysis
verifies every host manifest, exact JAR/native inventory, case identity, input tokens,
execution controls and pair completeness. Its scores and observations match the official
analysis exactly, apart from local evidence paths.

| Dataset | Models | Pinned publisher |
| --- | --- | --- |
| SQuAD2 exact match, 100 cases | 77% | 77% |
| SQuAD2 token F1 | 80.0297619% | 80.0297619% |
| SQuAD2 unanswerable accuracy | 74% | 74% |
| BoolQ accuracy, 100 cases | 90% | 90% |
| Execution failures / truncated answers | 0 / 0 | 0 / 0 |

The frozen descriptive regression rule passes for both datasets. One raw answer differs
(case 89); both answers receive the same exact-match/F1 score. Equal dataset scores are
not a claim that the model always answers correctly, or that every generated token matches.

## Exact scope

- Models source: `5344b5a2ff84a27548f0f2c4840c24f22d73c32d`.
- Vectors source: `b0aadc8d972f3504426f6e38fcb0a4b4a97a0333`.
- Core JAR: `263bff1aef74bac56d0597ba6b4d7ad52af846fac08b32e70e5d549ce5c8f099`.
- PTQ1_0 artifact: `53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3`.
- Publisher revision: `e8fd209c975c80aa7fb36a8423571ff3b1b7e955`.
- Frozen cases: `e1616ea8765a15bd569c38ab8a4367cefac9fa98756e976040fbbe0874270a7b`.
- Scored Models answers: `be7a03c3d1f5d9a68963c3a81bf456b040195bea22c2003a136e5ddb13a95fbc`.
- Scored publisher answers: `f8bd9e917eb8dcc98fc78aaca3a66fb589e9267b39e477cc1cd079153b94e84c`.

The run's `public-qa-summary` artifact contains the official report. Raw observations and
independent results remain outside the repository in the workspace's
`audit-artifacts/bonsai-final-qa-38345112860/`, including `independent-receipt.json`.

Cold and warm allocation regressions remain open. This result does not complete Bonsai's
production qualification and does not qualify a proposed BF16/SwiGLU runtime whose
arithmetic or routing differs. No marker or cleanup release follows from this result alone.

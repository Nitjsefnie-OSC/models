# Historical Bonsai correction evidence

These are **superseded results**, not the current runtime's release qualification. Long raw
artifact paths prevented the Windows native CI job from checking out the repository before any
test could run. The complete historical tree is now preserved outside the project; no Git
long-path setting, test waiver or shortened measurement is used to hide that failure.

The 295 original files (4,204,530 bytes) are preserved in the parent workspace archive
`audit-artifacts/2026-10-13-bonsai-history/bonsai-history.tar.gz`, SHA256
`39bbd945fecc6632252e6f51af171b353199155a4425544b4abda9b8a17603e8`.
Every member was read back and matched to its original size and SHA256 before removal.
[archive.json](archive.json) records all original relative paths, hashes, source revision and
archive identity. The complete original tree is also recoverable from Models Git revision
`6bbde0833fcf46de11bbb285c95c07cb5c9cf506`; the archive does not depend solely on an unversioned
local directory. Original JSON command/classpath strings remain immutable historical provenance.

| Historical comparison | Result and retained scope |
| --- | --- |
| Broad corrected core, run 38319081991 | Both architecture fixtures pass; this did not qualify performance or the normal released dependency. |
| BF16-only core, run 38319438508 | Both architecture fixtures fail transformed-batch logit parity. The failed assertions remain in the archive and [ablation summary](ablation-summary.json). |
| SwiGLU-only core, run 38320444703 | Both architecture fixtures still fail transformed-batch logit parity; other activation paths were required. |
| All activations, run 38321333816 | Both architecture fixture pairs pass; this is correctness evidence for that older artifact. |
| Expanded combined core, run 38323006687 | Four architecture/JDK configurations pass three tests each, without skipped tests inside those configurations. [Counts and identity](summaries.json) remain, with all original XML archived. |
| Serial allocation follow-up, run 38324642561 | Original configuration results and identity remain in [summaries.json]; they do not transfer to a later artifact. |
| BF16 component run 38319356761 | **Not qualified**: precision failures and increased ARM small-batch allocation remain explicit in the [result](bf16-result.md). Raw JMH, host and failure receipts are archived. |
| Older final-source CI/dry runs, run 38325753898 | Historical build/consumer checks passed on the recorded `13f0…` artifact. This was neither Central publication nor qualification of later source. |
| Local broad-core test and earlier archive pointers | Preserved with their original scope, including pointers to the previously externalized cold-attribution, scalar-control and scratch experiments. |

The [actual model header](bonsai2-actual-header.json) remains directly reviewable. No model
weights, current production code, public API, qualification dataset or acceptance threshold was
removed. Current decisions and runs are recorded in the
[Bonsai correction finding](../../docs/findings/bonsai-native-correction-2026-10-12.md) and the
[convolution regression finding](../../docs/findings/convolution-allocation-regression-2026-10-13.md).

To recover original files without restoring long paths into a Windows checkout, inspect them
from the archive or use `git show` at the recorded revision. Re-running old launchers is not a
current qualification procedure. The existing evidence-reference integrity check continues to
validate the short repository citations; no baseline exception was added.

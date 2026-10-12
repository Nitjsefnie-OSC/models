# Full public cohort with a stable diagnostic baseline

The unchanged x86 pipeline varied during early execution. A repeated-reference control replaced
five vector EXP/LOG sites in convolution-SiLU, SwiGLU and sigmoid/scaled-softplus, leaving other
arithmetic untouched; all 24 fixed-prompt reference runs then matched. Convolution alone did not
fix the whole pipeline. This is a baseline defect to repair in Vectors before production adoption.

Use that same explicit deterministic control for **all arms** of this isolated recurrence study.
Do not call it a released configuration or production qualification. It isolates the remaining
recurrence comparison while numerical contract/efficient production implementation is reviewed.
The instrumentation is not a proposed production mechanism. Full-logit equality has no tolerance.

Arms: current Java recurrence; four-accumulator Java recurrence using Vectors' existing staged
executor; aligned native sequential recurrence with matched threading, including one-token work.
Projections/attention, weights, prompt bytes, sampling/letter selection and batching are identical.
Count actual recurrence execution; require exact full logits from both candidate arms. The Java
candidate shares the existing Vectors executor, which native integration already configures to
park. No second production pool or model-specific user switch is proposed.

Run the full pinned 120-case JevBench plus 28-case SNIPS cohort. Three warmups/arm, all 148
correctness cases, then five counterbalanced repetitions of the already frozen 26-case timing
subset. Retain predictions, label scores, full-logit differences, whole-prefill and recurrence
durations. This is paired diagnostic performance under the controlled baseline, not a substitute
for generation/oracle/startup/memory qualification after the Vectors repair. No case is omitted
because it fails. Stop on exactness failure and retain the failure and Java replay.

Both hosts expose 16 processors/workers, matching available logical CPUs: AMD m6a.4xlarge and
Graviton m7g.4xlarge. This is a new prospectively recorded configuration, not a continuation of the
infeasible four-worker ARM run. Do not pool its timings with earlier four-worker measurements.
Keep pinned model/dependency/JVM/native compiler provenance. Native libraries use identical
projection code; only the recurrence candidate differs.

Maximum two hours per host, $3.50 combined compute/storage budget, live prices/quota before launch,
absolute deadline, uploads every minute and termination receipts. Preserve partial evidence on any
deadline/failure. Complete correctness on both public datasets is a separate gate from completing
the timing matrix, and both must be explicitly counted before reporting success.

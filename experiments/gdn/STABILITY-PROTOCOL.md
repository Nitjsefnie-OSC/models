# Reference stability diagnosis

The x86 discovery run failed exact logits for `easy-extraction-06`. A same-class host replay
of the first 31 cases did not reproduce the candidate difference: its Java baseline equaled
the earlier candidate's label logits. Keep both results; neither is permission to relax parity.

This is a diagnostic run, not qualification or a performance comparison. On a fresh x86 process,
repeat the unchanged Java recurrence on that exact 65-token prompt 24 times, fresh session each
time. Use the same pinned 4B artifact, four workers, native projections, JVM and dependencies.
Record full-logit differences against the first and previous observations, and first changed
recurrence input/state. Retain first/changed tensor arrays as raw big-endian float bits when a
change occurs. Copying/inspecting tensors contaminates timing; report those durations only for
runtime accounting. No candidate gets a correctness pass from this diagnostic.

Separately exercise the published Vectors sigmoid/softplus operation before and after compilation
on fixed inputs. This tests one suspected component; a failure would not alone establish the
cause of the whole-model observation. A pass does not exonerate other components or process
histories. Preserve compilation logs for localization.

One x86 m6a.4xlarge, maximum 25 minutes, $0.50 compute/storage budget. Immutable payload and
deadline/termination/upload handling follow the existing launcher. Stop after the diagnostic;
any further control requires a separately recorded experiment. Production routing stays unchanged.

## Follow-up v2: deterministic transcendental control

V1 reproduced changing full logits with unchanged Java recurrence. The first captured input change
was at recurrence call zero in query values. Independently, the published Vectors gate operation
changed output bits after repeated execution. Test this causal hypothesis on a new immutable
x86 payload, same budget/deadline: repeat the original reference diagnostic, then repeat with
only convolution's vector EXP replaced, and with all three relevant Vectors methods' vector
EXP/LOG calls replaced. Each lane uses StrictMath double precision rounded once to float;
all surrounding SIMD arithmetic remains the same. The methods are convolution-SiLU, SwiGLU,
and sigmoid/scaled-softplus. No production source or installed artifact is changed.

A benchmark-only Java agent changes these five call sites in the loaded published class. It
checks the exact call-site count and class verification, logs original/transformed class hashes
and executed calls, and terminates on a missing transformation. This avoids recompiling unrelated
dependency code. It is diagnostic instrumentation, not a proposed production mechanism.
Compare cold/hot outputs within each control; outputs need not equal the unstable original.
Any stable result establishes only the tested configuration. Defining a canonical numerical
contract, efficient implementation and production regression suite remains a separate change.

# ARM runtime replan, preserving the full workload

Early cohort-v4 ARM observations took about eight seconds per short request. The combined
843-observation workload therefore cannot reliably finish inside its two-hour deadline once
setup and longer prompts are included. This is a scheduling limitation, not permission to omit
cases or weaken the gate. Keep the frozen v4 payload unchanged.

Let v4 complete all 148 public cases across all three arms. After the uploaded evidence confirms
that complete exactness matrix, terminate its ARM worker and retain all observations, including
any timing rows it has already produced. Record the explicit cancellation reason/receipt. If
correctness itself fails, preserve the failure and diagnose it; no success may be inferred.

Run the full existing 26-case timing subset on a separate ARM m7g.4xlarge with identical 16-worker
settings, weights, deterministic activation control and recurrence arms. Do not select new cases:
the six first and six last JevBench cases plus the first two selected SNIPS cases per intent are
the same IDs fixed in v1/v4. Three warmups/arm, baseline/candidate exactness on those 26 cases,
then all five counterbalanced repetitions for all three arms: 477 observations. Assert every
count and exact-output result before reporting completion. The extra process must not be described
as a continuation of the original JVM. Keep each process's timings distinct.

One ARM worker, maximum two hours and $1.60 compute/storage, live quota/prices before launch,
immutable payload, minute uploads and termination receipt. No production path or numerical
contract changes. The x86 v4 run continues its original full protocol.

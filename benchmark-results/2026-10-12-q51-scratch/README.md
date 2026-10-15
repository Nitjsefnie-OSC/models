# Q5_1 caller scratch allocation

The public single-token and batched dispatch created a row array that the threaded Q5_1 path ignored. Serial Q5_1 calls also allocated one row each time. The fix reuses the already-existing thread-local Q5_1 scratch in both caller and worker paths. Q4_1 retains its prior allocation and arithmetic behavior.

On the local ARM JDK, a confined single-row projection with 16,384 columns allocated 102,480 bytes/call before and 36,928 after. The two-query call allocated 65,552 bytes before and zero afterward. Each eliminates exactly the 65,552-byte row array. This is an allocation diagnosis, not a whole-model performance qualification. Remaining single-token allocation is not claimed fixed.

The focused unit run passed 75 tests with no failures/skips, including shared versus confined Q5_1 exact output, growing/shrinking scratch dimensions, repeated changed inputs, public single and batched dispatch, and unchanged Q4_1 results. The probe warms 1,000 calls and measures 100 calls using ThreadMXBean; source is retained. Its first temporary assertion used a threshold below the unrelated remaining allocation and failed; the retained reporting probe makes no arbitrary acceptance claim.

The final cloud qualification already in progress uses the prior artifact and remains identified as such. Source-level numeric parity tests do not silently transfer whole-model resource results to this amended artifact.

## Convenience API follow-up

The remaining 36,928-byte single-token allocation was the convenience overload's four Q8 scratch arrays, although Q5_1 never quantizes its activation to Q8. Removing Q5_1 from that allocation branch and omitting its unused correction buffer in the second convenience overload reduces both measured single-token and two-query allocation to zero after 4,096 warm calls. The existing 75 focused tests and a final three-test exact-route rerun pass. Tests cover both convenience overloads for Q4_1 and Q5_1. Qwen's explicit caller-scratch overload does not take either changed convenience branch.

## Observable-output confirmation

The original zero-weight probe had a weak observability contract. The strengthened probe uses nonzero valid Q5_1 weights, changes an input element on every call, and stores the computed output into a volatile checksum on every call. Old and amended implementations produce identical checksum bits for both batches (`c20bdbac` and `c221bab0`). Allocated bytes/call remain 102,480 → 0 for single-token dispatch and 65,552 → 0 for two-query dispatch. Both the original and strengthened probes are retained.

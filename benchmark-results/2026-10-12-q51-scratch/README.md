# Q5_1 caller scratch allocation

The public single-token and batched dispatch created a row array that the threaded Q5_1 path ignored. Serial Q5_1 calls also allocated one row each time. The fix reuses the already-existing thread-local Q5_1 scratch in both caller and worker paths. Q4_1 retains its prior allocation and arithmetic behavior.

On the local ARM JDK, a confined single-row projection with 16,384 columns allocated 102,480 bytes/call before and 36,928 after. The two-query call allocated 65,552 bytes before and zero afterward. Each eliminates exactly the 65,552-byte row array. This is an allocation diagnosis, not a whole-model performance qualification. Remaining single-token allocation is not claimed fixed.

The focused unit run passed 75 tests with no failures/skips, including shared versus confined Q5_1 exact output, growing/shrinking scratch dimensions, repeated changed inputs, public single and batched dispatch, and unchanged Q4_1 results. The probe warms 1,000 calls and measures 100 calls using ThreadMXBean; source is retained. Its first temporary assertion used a threshold below the unrelated remaining allocation and failed; the retained reporting probe makes no arbitrary acceptance claim.

The final cloud qualification already in progress uses the prior artifact and remains identified as such. Source-level numeric parity tests do not silently transfer whole-model resource results to this amended artifact.

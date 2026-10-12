# Actual Bonsai allocation diagnostic

The immutable v4 worker records seven real Bonsai requests using the BF16 v9 candidate runtime (Models 0bc, Vectors ae337). JFR is diagnostic and its request timings are not used as an uninstrumented performance gate. The subsequent BERT/Q5 tasks use newer d2d0/afc artifacts; those are a separate experiment.

The recording identifies the dominant sampled vector allocations in `BFloat16Matrix.vectorMultiplyFourRows`, including its `accumulate`/`PanamaVectorUtilSupport.fma` operations. Grouped weighted samples are about 5.46 GB across these stacks. Activation prefill stacks contribute about 631 MB. SessionState constructor arrays contribute about 3.41 GB independently. These are sampled weighted estimates across the entire recording, not per-request exact allocation totals.

This is evidence of actual caller-context vector allocations; it does not establish that every byte is a new regression relative to the published runtime. A separate fixed scalar activation control is being evaluated.

Inspect the original recording with `jfr print --json --stack-depth 96 --events jdk.ObjectAllocationSample bonsai-candidate.jfr`. The print tool otherwise defaults to five frames despite the recording retaining deeper stacks. The grouped JSON retains all application frames.

Original S3 prefix: `s3://models-qual-077051030817/results/gdn-proof-q5-final-v4/x86/`. Payload SHA256: `7e3482e42fd3f70d7933b27ba5625bf8269be70ff2e310309b7aea5b542e208e`. Runtime JAR hashes are listed separately to avoid relying on stale inherited descriptive fields in the original experimental metadata.

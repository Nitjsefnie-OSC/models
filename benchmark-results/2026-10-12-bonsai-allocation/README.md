# Actual Bonsai allocation diagnostic

The immutable v4 worker records seven real Bonsai requests using the BF16 v9 candidate runtime (Models 0bc, Vectors ae337). JFR is diagnostic and its request timings are not used as an uninstrumented performance gate. The subsequent BERT/Q5 tasks use newer d2d0/afc artifacts; those are a separate experiment.

The recording identifies the dominant sampled vector allocations in `BFloat16Matrix.vectorMultiplyFourRows`, including its `accumulate`/`PanamaVectorUtilSupport.fma` operations. Grouped weighted samples are about 5.46 GB across these stacks. Activation prefill stacks contribute about 631 MB. SessionState constructor arrays contribute about 3.41 GB independently. These are sampled weighted estimates across the entire recording, not per-request exact allocation totals.

This is evidence of actual caller-context vector allocations; it does not establish that every byte is a new regression relative to the published runtime. A separate fixed scalar activation control is being evaluated.

Inspect the original recording with `jfr print --json --stack-depth 96 --events jdk.ObjectAllocationSample bonsai-candidate.jfr`. The print tool otherwise defaults to five frames despite the recording retaining deeper stacks. The grouped JSON retains all application frames.

Original S3 prefix: `s3://models-qual-077051030817/results/gdn-proof-q5-final-v4/x86/`. Payload SHA256: `7e3482e42fd3f70d7933b27ba5625bf8269be70ff2e310309b7aea5b542e208e`. Runtime JAR hashes are listed separately to avoid relying on stale inherited descriptive fields in the original experimental metadata.

## Completed exact scalar control

All seven requests and their nine owned full-logit snapshots match exactly. Replacing only SwiGLU and causal convolution SiLU with fixed scalar StrictMath arithmetic lowers warm allocated bytes from 1.243 GB to 487.7 MB and peak RSS from 8,706,764 to 8,128,348 KiB. The unchanged BF16 matrix class therefore stops most of its observed allocations when the surrounding activation code changes. This demonstrates caller/compiler-context sensitivity; it does not mean allocation was confined to the modified methods.

Mean measured model time is 40.67 seconds in the instrumented vector arm and 42.49 seconds in the scalar arm. This single diagnostic pair cannot establish latency acceptance because only the vector arm has JFR enabled. The scalar source is retained as an experiment, not selected as the production implementation.

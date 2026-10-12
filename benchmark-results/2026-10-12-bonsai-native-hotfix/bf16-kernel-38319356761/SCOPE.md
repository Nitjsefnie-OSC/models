# Independent BF16 kernel experiment

[Run 38319356761](https://github.com/integrallis/vectors/actions/runs/38319356761)
retains every one of the twelve paired processes on both hosts. All latency
confidence intervals are below parity, but the frozen precision target is unmet
in five cases and ARM small-batch allocation increases. This is not an all-pass
qualification. The broad combined model run is separate evidence.

REPORT.md is the original report at the time these kernels completed. Its final
Bonsai causal statement was superseded by broader fresh-session testing: complete
activations alone pass the original JUnit cases but fail full-logit repeatability;
the combined correction is required by the broader checks.

Raw text/JSON/JUnit receipts are copied here. Binary JARs remain in the downloaded
artifact under /tmp/bf16-hotfix-supported-hosts and the linked GitHub artifact;
receipt-sha256.json retains their hashes. Third-party benchmark libraries are not
vendored into this repository.

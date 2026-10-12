# Real activated-adapter framework fixtures

The immutable payload in `payload.json` uses Models d2d0fb4f and Vectors core SHA
79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7.
Other Vectors modules remain published 0.1.29. This is a composed candidate check,
not a released dependency-chain claim. Weights and adapter files are verified by SHA-256.
The worker receipt records its 40-minute / $1 cap and absolute termination deadline.

The initial uploader incorrectly used `aws s3 sync`, but the worker role permits object
uploads without bucket listing. Uploads therefore failed with ListObjectsV2 AccessDenied.
The inference process remained active. At 21:52–21:55 UTC, EC2 Instance Connect was used
with the existing public key to inspect the worker and upload its retained output using
`aws s3 cp --recursive`. No IAM permission was broadened. An exact-command wrapper
repairs the already-running Python process's final upload; the periodic uploader was
replaced with `cp --recursive`. `raw/upload-repair.sh` records the wrapper. Source worker
and runner now use `cp` directly. These telemetry repairs do not change model artifacts
or inference arguments. Original upload errors are retained in `raw/upload.log`.

The literal LangChain4j fixture repeats the weather tool call. Its assertion is caught by
the framework and returned as a tool error to the model, explaining the long-running
loop. This is a failed model behavior, not evidence of a deadlock. The source fixture
now sets one permitted sequential tool invocation, matching its existing one-call
assertion; the frozen worker retains the original fixture and 900-second process bound.
This fixture change does not repair or qualify the model's tool selection.

Local bounded attempts are retained separately in
`benchmark-results/2026-10-12-runtime-cleanup/activated-local-bounds/`.

The frozen remote run completed with failures: LangChain4j exit 124 at its 900-second
limit, Spring AI exit 1 after 18 seconds, both showing a second weather call. The owned
host i-07c384ce26d222198 was verified terminated at 22:01 UTC. This is failed qualification.

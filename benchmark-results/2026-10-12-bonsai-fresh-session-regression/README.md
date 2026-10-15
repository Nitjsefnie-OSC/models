# Bonsai fresh-session full-logit regression

The existing fixture checked all eight publisher tokens but only the winning token for a fresh
session and reset. This new test retains the first cold explicit session's eight complete logit
arrays, compares two subsequent explicit sessions bit for bit, and then compares implicit-session
and reset prefill against that same cold baseline. Every retained array is cloned immediately.
All eight publisher tokens are still checked in each session. No tolerance is introduced.

The native Bonsai task forks once per test class and explicitly exposes four CPUs, matching the
reproducing macro. Other runtime settings remain defaults except the same 64-token context cap.
The original fixture class and its assertions are unchanged. The new task's class-file includes
avoid launching unrelated filtered test workers while preserving a separate JVM for each class.

Local Temurin 25.0.3 reproduces a published-core failure at repeat 1 / step 0 / token 0 despite identical
publisher tokens. The exact combined core passes all 24 complete snapshots and implicit/reset
checks. The XML records the exact JDK and each snapshot hash. A temporary local validation init
script selects the immutable candidate JAR and removes the published core from the classpath;
it is not a production dependency change or a substitute for released-artifact CI. The production
task continues to resolve the repository's configured dependency.

The earlier Linux literal-fixture workflow used floating Temurin 25 (observed 25.0.4.1), while the
macro used pinned 25.0.3. These receipts do not claim JDK/architecture equivalence: both versions
must be checked on the supported Linux architectures before drawing a cross-host conclusion.

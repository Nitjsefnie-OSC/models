# Dependency-ordered cleanup release

This is the execution plan for the current cleanup, not a publication receipt. Candidate versions
are Vectors 0.1.30, Models 0.3.59, and ModelJars 0.1.58. No member of this train is published yet.
Existing authorizations cover completion and publication through these gates.

## 1. Freeze and qualify the corrected runtime

Retain immutable source revisions, compiled artifact hashes, JDK/native compiler versions,
weights, data, prompts, observed route counters, and every failed control. Combine the exact
BF16 and activation fixes with the executor lifecycle guard; combine Models' ownership,
recurrence, Q4 rounding, Q5 selection/allocation, and shared-prefix corrections. A later
source change invalidates affected evidence and needs an explicitly bounded rerun.

Run supported-host numerical/cold-hot/reset/grouped checks and paired whole-model measurements.
Preserve the declared latency, throughput, allocation, RSS, startup and task-quality gates.
Bonsai also requires its exact-artifact publisher-reference verdict and 200-case public QA
comparison. The currently observed activation/resource regressions are release blockers.
No model marker is promoted merely because a library build passes.

## 2. Publish and verify Vectors

Finish PR #98 with the qualified final source, changelog, full build/compliance, supported-host
checks and dry-run release. Merge the approved source through passing CI. Dispatch `release.yml`
with `dry_run=true`; once it passes for the exact release source, dispatch `dry_run=false` with
`skip_deploy=false`. Do not use GitHub-release-only mode to stand in for Central publication.

Wait for the recorded Sonatype deployment to reach `PUBLISHED`, then fetch POMs/JARs from
Central. Verify signatures/checksums, embedded release-dependency manifests, and the independent
Maven/Gradle consumer graphs. Record the deployment, release tag and exact artifact hashes.

## 3. Update, qualify, publish and verify Models

Only after Vectors is verified on Central, update the exact Vectors dependency and regenerate
locks. Run `verifyReleaseTrain`, `verifyPublishedConsumerGraph`, `complianceCheck`, all required
native platforms, framework compatibility, real model fixtures and final release dry run.
Run consumers with fresh caches so a local sibling build cannot mask missing publication.

Merge PR #266 only after its remaining gates pass. Dispatch the Models release workflow first
with `dry_run=true`, then `dry_run=false` and `skip_deploy=false` on the qualified source. Verify
all 16 publication modules from Central, their upstream dependency manifests, and fresh consumer
graphs. Preserve CUDA's separately scoped hardware result; a skipped device test is not a pass.

## 4. Update and publish ModelJars, CLI and site

Pin every Models dependency to the verified release and every Vectors dependency to its matching
release; update composition coordinates and regenerate locks/catalog metadata consistently.
Run catalog/profile/component/composition gates, Java and CLI tests, real artifact fixtures,
`verifyReleaseBundle`, and independent consumer graphs. Preserve previous model qualifications
with their original runtime/provenance; derive new ones only from completed evidence.

Run `publish.yml` with `target=verify`, then publish the exact checked bundle with
`target=maven-central` and explicit release version. Record the USER_MANAGED deployment ID,
validate the staged inventory, finalize that deployment, and verify Central artifacts using
`python3 scripts/verify-maven-runtime.py --central --output build/central-consumers`.
Publish newly qualified model markers only after their own gates pass. Bonsai stays excluded
if its production qualification is unresolved.

Create the verified release and run `cli-release.yml` with its release tag/ref. Check every
supported binary and checksum, update the existing Homebrew/Scoop release channels, and deploy
Pages after library and marker coordinates are downloadable. SDKMAN publication follows its
existing enabled/approved channel setting. Verify CLI output and site dependency coordinates.

## 5. Update local installations and finish branch cleanup

After Central and CLI verification, update all three primary checkouts and local installations,
including `/usr/local/bin/modeljars`. Check executable resolution (`which -a modeljars`), reported
version, downloaded binary checksum, and a released-artifact smoke test. Preserve dirty original
work in named branches/commits before moving those checkouts. Do not install an unpublished
candidate under the guise of the released CLI.

Consolidate useful work into the three release PRs plus explicitly continuing research branches.
Delete a branch only after its unique commits and dirty files are accounted for; retain failed
measurement evidence. Remove stale worktree metadata only when its directory is absent.

## Build guards already implemented

Each published JAR embeds `META-INF/jvm-ai/release-dependencies.json`. Downstream builds compare
actual upstream manifests and resolved compile/runtime/test graphs, not only version properties.
Mutation tests reject stale or forced versions, dynamic dependencies, reverse edges, project
substitution, and absent/inconsistent manifests. Fresh Maven and Gradle consumers verify staged
metadata independently; publication repeats that validation against Central.

The shared external policy aligns governed Jackson, SLF4J, Netty, AWS, Caffeine and other selected
libraries across the train. Deliberate Spring/LangChain4j compatibility profiles remain distinct
and are tested rather than forced across incompatible major lines. See
[the reviewed dependency policy](../../gradle/DEPENDENCY-POLICY.md).

## Completion receipt

Record each source/tag, workflow URL, deployment ID, published artifact/checksum, resolved
upstream version, local installation verification, qualification report and cleaned branch.
If any required gate fails, record that failure and continue fixing it; do not describe this
plan or a dry run as a successful release.

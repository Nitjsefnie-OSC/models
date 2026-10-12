# Remove the inactive native Gated DeltaNet experiment

The production Rust dispatcher returned false for both recurrence capabilities. The crate still
compiled and exported sequential/grouped recurrence, and Java still loaded a recurrence symbol
unconditionally. The code was retained for experiments, not because a production route needed it.
Version history already preserves the experiment; shipping it was unnecessary.

## Change

- Delete Rust recurrence jobs, worker dispatch, sequential/chunked arithmetic, exports, and their
  now-obsolete tests and timing probe.
- Remove FFM descriptors, handles, invocation methods and recurrence capability enum entries.
- Remove unused recurrence methods from the injected-kernel SPI and Qwen dispatch branches.
  The existing Java recurrence and active-row state mapping are unchanged.
- Remove the diagnostic describing Java recurrence as a missing-native performance cliff.
- Preserve the AVX2 detection and scaled-add helper used by active attention, rename them for
  attention, and test attention through the Java dispatcher as well as directly through FFM.
- Advance the native ABI from 6 to 7. Retired capability bits 18 and 22 remain reserved. Bindings
  and platform artifacts must be upgraded together. Custom recurrence SPI overrides must be
  removed; the changelog documents this source API change.

## Verification

The new binary-export test first failed against the old library because the recurrence symbol
was present. It passes after removal. The Rust capability test also verifies the retired bits
are clear. A separate probe using the actual previous library confirms rejection with
`unsupported native kernel ABI 6; expected 7`.

The Java backend test task completed with 969 passed, 18 skipped, and no failures. The separate
real-weight tasks below exercise the affected recurrence; the general task's skipped tests are
not counted as validated model fixtures.

`backend-native:check` passes: 50 Java tests, 27 Rust tests, Clippy, SpotBugs, formatting,
dependency policy and platform-JAR ABI/checksum verification. Native instruction coverage is
80.34%, above the unchanged 80% requirement. The initial post-removal check failed coverage;
exercising the retained attention dispatcher against the existing numerical reference closed
that gap without lowering the gate.

The real-weight `backend-java:qwen35GgufLayoutTest` and
`backend-java:qwen35GroupedGdnCompatibilityTest` tasks pass using installed, verified Qwen
0.8B and 4B fixtures, including pinned external token oracles and public-backend generation.
All five real-weight tests pass without skips. Compilation of main and test sources across all
modules also passes, as do Java-backend SpotBugs and formatting checks.

Commands:

```sh
./gradlew :backend-java:test :backend-native:check
./gradlew :backend-native:check :backend-native:jacocoTestReport \
  :backend-java:qwen35GgufLayoutTest :backend-java:qwen35GroupedGdnCompatibilityTest
./gradlew compileJava compileTestJava :backend-java:spotbugsMain :backend-java:spotlessCheck
```

These are local source validations, not a release or catalog requalification. No speedup is
claimed from deleting unreachable recurrence code. The independent BF16/Bonsai release work and
the proof campaign for the remaining shims are separate outstanding work.

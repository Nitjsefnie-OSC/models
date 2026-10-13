# Archived Vectors scratch evidence

Historical preservation before branch consolidation. These reports were copied byte-for-byte; this archive does not represent a new test run or release qualification. `manifest.json` records original worktree paths, immutable source commits, scopes and SHA-256 hashes for every copied file.

PR100 at `5046f5f1018da9f3c44c5ba8ad682301d251d88c` owns the combined correction. The superseded combined scratch source is preserved by its separate annotated audit tag; it must not overwrite PR100. Activation-only and BF16-only sources are experimental controls, not additional fixes to merge. The activation contract evidence is already incorporated in PR100; its small local report tree is preserved here for completeness.

## Rejected convolution boundary

`convolution-boundary-rejected` preserves all 41 committed evidence files, the three-file experimental harness, and ignored local reports from source `944ae2c2492dd572f477e15acd213fcd2ae88da7`. The candidate runtime is preserved in the tagged Git history, not copied into production. It failed to establish the proposed cold allocation/latency fix: warm allocation was already zero in the broad control, and cold allocation remained about four times published. This experiment remains **REJECTED**, and its local diagnostic does not establish production performance. See its original README and protocol for numerical checks, uncertainty and scope.

## Retention

Annotated `audit/2026-10-12/*` tags preserve the five source histories. Worktree directories are retained. Generated class files, JARs and build caches are reproducible from those sources and are not copied here; historical report trees, including Gradle report support files and compact binary test records, are retained. Active workflow input revisions `91dd64ae` and `6893a587` remain reachable through their existing continuing branches; this operation does not modify them.

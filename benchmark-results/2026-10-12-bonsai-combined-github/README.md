# Combined BF16 and activation actual-model comparison

Run38321278750 pins Models4f3d3e9a and Vectors6893a587. Both arms use identical
Models/native artifacts; only Vectors core changes from released0.1.29 to the combined
candidate. The frozen protocol, complete artifact hashes and per-entry inventories are
retained with every shard. Candidate core SHA256 is
e3e7b0ab79d0630bcad65c4cbe9fd356cf589dd159eba8e6c0aa402db24bd33c.

Qwen BF16 is complete: ten same-host process pairs on each architecture, thirty distinct
measured requests across the two public datasets, all repeated candidate hashes exact.
Baseline fixture failures remain visible. Classification counts are unchanged (10/15
JEVBench,14/15SNIPS) on both architectures. These are the declared small comparison cohorts,
not a general model-quality qualification.

The complete Qwen analyses show faster whole requests and lower allocation/RSS. Full
acceptance remains unresolved: the ARM startup confidence interval crosses the limit,
and several metrics do not meet the frozen two-percentage-point precision target.
No samples are discarded and no gate is relaxed.

Bonsai's four shards remain in progress at this evidence checkpoint. No complete Bonsai
performance verdict or overall qualification is claimed. The all-activation-only candidate's
separate fresh-session failures are in the sibling all-activation report, never pooled here.

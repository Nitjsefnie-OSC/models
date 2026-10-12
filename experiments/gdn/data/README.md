# Public datasets

JevBench: https://github.com/fstandhartinger/jevbench — retained easy/original public cohort,
120 cases. Prompts follow the current shipped evidence/rubric/instruction/options layout.
This is a current-source reproduction; it does not assert that every historical runtime detail
is identical. Immutable source revisions and hashes are in sources.json.

SNIPS: https://github.com/sonos/nlu-benchmark — first four validation examples per intent in
source order, 28 total, all seven labels retained. This is an independent regression cohort,
not the full SNIPS benchmark or an estimate of state-of-the-art accuracy.
Citation: Coucke A. et al., "Snips Voice Platform: an embedded Spoken Language Understanding
system for private-by-design voice interfaces." 2018. Original source files are retained.

Select timing samples prospectively: first and last six JevBench cases and first two per SNIPS
intent. No case is excluded from correctness because of performance or its answer.

# Control record: localise the Laya forward-pass residual

Written before creation, as `VPS-BENCHMARK-OPERATIONS.md` requires.

| field | value |
| --- | --- |
| Experiment | Localise the 0.573 logit residual between `ModernBertDecisionForwardPass` and `oracle/REFERENCE.json` by comparing per-stage activation fingerprints |
| Why remote at all | `torch 2.14.1` publishes no macOS x86_64 wheel; this dev box is x86_64 Mac, its newest torch is 2.2.2, and the `transformers` `laya` needs requires >= 2.6. Measured, not assumed — see `REPRODUCE.md` |
| Provider / region / plan | Hetzner Cloud / nbg1 / `cx33` (4 vCPU, 8 GB; `cx32` is deprecated) — 8 GB because the checkpoint is 369M parameters at float32 plus torch |
| Expected hourly | ~EUR 0.016/h, one-hour minimum charge |
| Maximum authorized spend | EUR 1.00 |
| UTC creation deadline | 2026-10-22 |
| UTC deletion deadline | 2026-10-22, immediately after evidence is copied and verified |
| Correctness gate | the script prints a fingerprint for all 30 blocks and raw logits equal to `REFERENCE.json`'s `has_invoice` `rawLogits` to 1e-4; if its own logits do not reproduce, the dump is not evidence about our Java |
| Performance gate | none. This is a correctness artifact and no timing is claimed from it |
| Local evidence destination | `experiments/laya-modern-bert/oracle/INTERMEDIATES-<case>.txt` |
| SSH key | `vectors-bench-v2` (Hetzner id 114663461), matched to `~/.ssh/id_ed25519.pub` by fingerprint `SHA256:fseILVYhZFEBXRXkKm3KPUaoat9GGIZiXnpb/WnYDuM`, not by label |
| Secrets on host | none. `projects/.env` is not copied; the checkpoint and wheels are public downloads |

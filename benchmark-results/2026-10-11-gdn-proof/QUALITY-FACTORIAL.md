# Public-cohort arithmetic factorial

> Historical measurements and decisions from the recorded revisions below, not a current release verdict. Raw experiments, failed controls, scripts and captures are preserved in the [verified external archive](ARCHIVE.md). Current status is in [runtime cleanup](../../docs/findings/runtime-cleanup-2026-10-12.md).

Four fixed artifact arms reproduce the x86 small-model score change: published89/148, activation-only88/148, corrected-projection/Java artifact88/148, combined86/148. The combined loss is three net examples (seven correct-to-wrong and four wrong-to-correct); thirteen labels change. No prompts, labels, thresholds, or arithmetic were tuned against these cases.

The activation change replaces an approximation that failed the declared numerical contract with deterministic fdlibm arithmetic. The projection change fixes independently reproduced batched-versus-single Q4_K rounding disagreement. These are correctness-motivated changes; the factorial identifies which artifact change affects each ranking but does not establish that every later internal tensor is defect-free. The broad corrected-projection arm also carries documented ownership/Java corrections, so it is not advertised as a one-instruction isolation. The task-quality loss remains explicit and is not a passed gate.

| Case | Expected | Published prediction (margin) | Activation only | Projection/Java correction | Combined |
|---|---|---|---|---|---|
| easy-intent-02 | change_address | change_address (0.062452) | track_order (0.197095) | track_order (0.471367) | track_order (0.350026) |
| easy-intent-10 | turn_off_lights | turn_off_lights (0.263250) | turn_off_lights (0.143601) | set_alarm (0.123993) | set_alarm (0.214779) |
| easy-tool_selection-05 | create_calendar_event | create_calendar_event (0.131214) | create_calendar_event (0.039837) | create_calendar_event (0.045506) | get_weather (0.016782) |
| original-policy-02-0 | yes | no (0.075819) | no (0.074327) | yes (0.000599) | yes (0.118664) |
| original-policy-02-1 | yes | no (0.064438) | yes (0.095342) | yes (0.009896) | yes (0.017544) |
| original-policy-03-1 | yes | yes (0.111379) | no (0.211084) | yes (0.024063) | no (0.571011) |
| original-policy-05-1 | no | no (0.053961) | no (0.200128) | yes (0.085747) | yes (0.073159) |
| original-intent-06-1 | status | status (0.083572) | cancel (0.257778) | status (0.128058) | cancel (0.104162) |
| original-ordinal-04-0 | 3 | 1 (0.071217) | 2 (0.062893) | 1 (0.022453) | 2 (0.009405) |
| original-routing-03-1 | coding_agent | coding (0.386368) | coding (0.197155) | coding_agent (0.216005) | coding_agent (0.259895) |
| original-routing-06-0 | general | coding_agent (0.072918) | coding_agent (0.057756) | coding_agent (0.059621) | math (0.055437) |
| snips-AddToPlaylist-1 | AddToPlaylist | AddToPlaylist (0.352326) | AddToPlaylist (0.642323) | AddToPlaylist (0.007599) | PlayMusic (0.010319) |
| snips-BookRestaurant-3 | BookRestaurant | AddToPlaylist (0.004002) | BookRestaurant (0.166477) | AddToPlaylist (0.062748) | BookRestaurant (0.045976) |

Margins are top-label minus second-label scores, using the same retained label scores in every arm. Full scores and all148 prefill hashes are retained in quality-factorial-v1-analysis.json and raw stdout. Three changed decisions are especially close after correction (combined margins0.009405,0.010319,0.016782); other changes have larger margins after accumulated arithmetic differences, so calling every case a near-tie would overstate the evidence.

The complete worker exited successfully and its owned instance is confirmed terminated.

#!/usr/bin/env python3
"""Produce the reference artifact our Java implementation of Laya is written against.

This is the only thing in the Laya work that runs third-party Python, and it runs it for one
reason: a decoder validated against our own scalar reference can share a misunderstanding with it
and still pass. The runbook requires an external oracle, and for the decision head the reference
implementation is the only one that exists -- llama.cpp carries a `modern-bert` encoder graph but
no decision head at all, and its reranker path applies the `cls.*` tensors in a different order
(dense, GELU, norm) from the order this checkpoint trained them in (norm, dense, GELU, out). So
the encoder half can be cross-checked against llama.cpp later; the head half can only be checked
here.

Pinned on purpose:
  * checkpoint revision 7b928d828b7b0e022f929d9bd2e44165aa270148, which is the revision
    `ggml-org/Laya-GGUF` was converted from (its `.src_sha` says `PRIMARY=7b928d82...`). The
    `laya` package's own PINNED_REVISIONS names a *different* revision (55cf4c4e...), so taking
    the package default would have produced an oracle for weights the artifact does not contain.
  * float32 everywhere, CPU, no autocast: this is a correctness artifact and no timing is claimed
    from it.

Writes REFERENCE.json: per case, the token ids, the option markers, the parallel-layout position
and option ids, the raw per-option logits, the temperature that applies, and the probabilities.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys

os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch

from laya.agent import Agent
from laya.common import QTYPES, build_sequence, parallel_layout, temp_bucket

HERE = pathlib.Path(__file__).resolve().parent
REVISION = "7b928d828b7b0e022f929d9bd2e44165aa270148"
MODEL = "convaiinnovations/laya"

# One state, and questions that exercise every type and every temperature bucket the GGUF
# declares: noul (2), choice at 2 / 3-5 / 6-10 / 11+, and score. A bucket with no case in it is a
# bucket our implementation could get wrong without failing anything.
STATE = (
    "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our "
    "plan. The invoice number is 88412 and the card ends 4242."
)

QUESTIONS = {
    "churn_risk": {"t": "noul", "ins": "Does the user threaten to cancel or leave?"},
    "has_invoice": {"t": "noul", "ins": "Does the message contain an invoice number?"},
    "billing_or_not": {
        "t": "choice",
        "ins": "Is this about billing?",
        "crit": {"billing": "invoices, payments, refunds", "other": "everything else"},
    },
    "department": {
        "t": "choice",
        "ins": "Which department should handle this?",
        "crit": {
            "billing": "invoices, payments, refunds",
            "technical": "bugs, outages, system errors",
            "account": "logins, passwords, profile changes",
            "other": "everything else",
        },
    },
    "queue_eight": {
        "t": "choice",
        "ins": "Which queue should this ticket enter?",
        "crit": {
            "billing": "invoices, payments, refunds",
            "technical": "bugs and outages",
            "account": "logins and profiles",
            "shipping": "delivery and tracking",
            "returns": "returns and exchanges",
            "legal": "contracts and compliance",
            "sales": "upgrades and new purchases",
            "other": "everything else",
        },
    },
    "intent_twelve": {
        "t": "choice",
        "ins": "Which intent does the message express?",
        "crit": {
            "refund": "asks for money back",
            "cancel": "wants to end the service",
            "upgrade": "wants a larger plan",
            "downgrade": "wants a smaller plan",
            "bug": "reports a defect",
            "outage": "reports unavailability",
            "login": "cannot sign in",
            "invoice": "asks about an invoice",
            "address": "changes an address",
            "feedback": "gives an opinion",
            "question": "asks how something works",
            "other": "none of these",
        },
    },
    "urgency": {"t": "score", "ins": "How urgent is this?",
                "crit": ["not urgent", "soon", "blocking"]},
    "severity_five": {"t": "score", "ins": "How severe is the customer's situation?",
                      "crit": ["trivial", "minor", "moderate", "serious", "critical"]},
}


def main() -> int:
    torch.manual_seed(0)
    torch.set_grad_enabled(False)
    agent = Agent(MODEL, device="cpu", revision=REVISION)
    model = agent.model
    model.eval()
    tok = agent.tok
    cfg = agent.cfg

    out = {
        "model": MODEL,
        "revision": REVISION,
        "laya": __import__("laya").__version__,
        "torch": torch.__version__,
        "qtypes": QTYPES,
        "config": {k: cfg[k] for k in sorted(cfg) if isinstance(cfg[k], (int, float, str, bool))},
        "temperature": [float(x) for x in agent.temperature],
        "temperatureRaw": [float(x) for x in agent.temperature_raw],
        "temperatureByOptions": {k: float(v) for k, v in agent.temperature_by_options.items()},
        "temperatureByOptionsRaw": {k: float(v)
                                    for k, v in agent.temperature_by_options_raw.items()},
        # The reference refuses a temperature outside [0.5, 5] and clamps it, warning that the
        # affected entries are uncalibrated. This checkpoint ships choice.11 = 0.100583, so the
        # clamped and raw maps differ and an implementation that used the header value verbatim
        # would disagree with the reference on every 11-or-more-option question.
        "clamped": {k: [float(agent.temperature_by_options_raw[k]),
                        float(agent.temperature_by_options[k])]
                    for k in agent.temperature_by_options
                    if abs(float(agent.temperature_by_options_raw[k])
                           - float(agent.temperature_by_options[k])) > 1e-9},
        "specialTokens": {
            "cls": tok.cls_token_id, "sep": tok.sep_token_id, "mask": tok.mask_token_id,
            "pad": tok.pad_token_id, "unk": tok.unk_token_id,
            "vocabSize": len(tok),
        },
        "state": STATE,
        "cases": [],
    }

    max_len = int(cfg.get("max_len", 512))
    head_max_len = int(cfg.get("head_max_len", 192))

    for name, q in QUESTIONS.items():
        ids, markers, layout = build_sequence(
            tok, STATE, q, max_len=max_len, head_max_len=head_max_len, return_layout=True)
        qtype = QTYPES[q["t"]]
        input_ids = torch.tensor([ids], dtype=torch.long)
        attention_mask = torch.ones_like(input_ids)
        marker_pos = torch.tensor([markers], dtype=torch.long)
        marker_mask = torch.ones_like(marker_pos, dtype=torch.bool)
        position_ids = torch.tensor([layout["position_ids"]], dtype=torch.long)
        option_ids = torch.tensor([layout["option_ids"]], dtype=torch.long)
        logits, _act = model(
            input_ids=input_ids, attention_mask=attention_mask, marker_pos=marker_pos,
            marker_mask=marker_mask, qtype=torch.tensor([qtype], dtype=torch.long),
            position_ids=position_ids, option_ids=option_ids)
        row = logits[0].tolist()

        # Which temperature applies. The key is built by the reference's own `temp_bucket`, not
        # by a reimplementation of it: the GGUF spells these buckets `choice.3_5` and `choice.11`
        # while the reference spells them `choice:3-5` and `choice:11+`, so a hand-built key
        # silently misses every bucket and falls back to the generic per-type temperature. That is
        # exactly what the first run of this script did.
        options = len(markers)
        key = temp_bucket(qtype, options)
        temperature = agent.temperature_by_options.get(key, agent.temperature[qtype])
        scaled = [x / float(temperature) for x in row]
        exp = torch.softmax(torch.tensor(scaled), -1).tolist()

        out["cases"].append({
            "question": name,
            "type": q["t"],
            "qtype": qtype,
            "options": options,
            "temperatureKey": key,
            "temperature": float(temperature),
            "tokenCount": len(ids),
            "ids": ids,
            "markers": markers,
            "positionIds": layout["position_ids"],
            "optionIds": layout["option_ids"],
            "rawLogits": row,
            "scaledLogits": scaled,
            "probabilities": exp,
        })
        print(f"{name:<16} {q['t']:<6} options={options:<2} key={key:<14} "
              f"T={float(temperature):.6f} tokens={len(ids)} argmax={max(range(len(exp)), key=exp.__getitem__)}")

    # The public API over the same inputs, so the artifact records that the hand-driven path above
    # agrees with what a caller actually gets rather than only with itself.
    public = agent.predict(STATE, {
        name: {"type": q["t"], "instructions": q["ins"],
               **({"criteria": q["crit"]} if "crit" in q else {})}
        for name, q in QUESTIONS.items()})
    out["publicApi"] = json.loads(json.dumps(public, default=str))

    (HERE / "REFERENCE.json").write_text(json.dumps(out, indent=1) + "\n")
    print(f"\nwrote REFERENCE.json: {len(out['cases'])} cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Dump the reference's hidden state after every stage, to localise a logit discrepancy.

Why this exists. Our Java forward pass agrees with `REFERENCE.json` on the chosen option for all
eight cases, but its worst logit deviation is 0.573 against a tolerance of 0.15 declared before the
first run. Eleven structural hypotheses were measured and every one made it worse, which is the
honest limit of comparing one scalar at the end of thirty blocks: an end-to-end number says that
two implementations differ, never where.

This prints the same per-stage fingerprint the Java side prints -- mean, standard deviation, L2 norm
and the first element of the whole hidden state -- so the two can be lined up and the first
disagreeing stage read off.

Same pins as oracle.py, for the same reasons: checkpoint revision 7b928d82 (the revision
`ggml-org/Laya-GGUF` was converted from, NOT the `laya` package's own default), float32, CPU, no
autocast.

Usage: python oracle/intermediates.py [case]      (default: has_invoice, the worst case)
"""

from __future__ import annotations

import json
import os
import pathlib
import sys

os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch

from laya.agent import Agent
from laya.common import QTYPES, build_sequence

HERE = pathlib.Path(__file__).resolve().parent
REVISION = "7b928d828b7b0e022f929d9bd2e44165aa270148"
MODEL = "convaiinnovations/laya"

STATE = (
    "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our "
    "plan. The invoice number is 88412 and the card ends 4242."
)

QUESTIONS = {
    "churn_risk": {"t": "noul", "ins": "Does the user threaten to cancel or leave?"},
    "has_invoice": {"t": "noul", "ins": "Does the message contain an invoice number?"},
}


def fingerprint(name: str, tensor: torch.Tensor) -> str:
    flat = tensor.detach().reshape(-1).double()
    mean = flat.mean().item()
    sd = flat.std(unbiased=False).item()
    l2 = flat.norm().item()
    return f"{name:<12} mean={mean:+.6f} sd={sd:.6f} l2={l2:.4f} first={flat[0].item():+.6f}"


def main() -> int:
    case = sys.argv[1] if len(sys.argv) > 1 else "has_invoice"
    if case not in QUESTIONS:
        print(f"unknown case {case}; known: {sorted(QUESTIONS)}", file=sys.stderr)
        return 2

    torch.manual_seed(0)
    torch.set_grad_enabled(False)
    # Constructed exactly as oracle.py does it; this API was read from oracle.py, not guessed.
    agent = Agent(MODEL, device="cpu", revision=REVISION)
    model = agent.model
    model.eval()
    tok = agent.tok
    cfg = agent.cfg

    q = QUESTIONS[case]
    ids, markers, layout = build_sequence(
        tok, STATE, q,
        max_len=int(cfg.get("max_len", 512)),
        head_max_len=int(cfg.get("head_max_len", 192)),
        return_layout=True,
    )
    print(f"# case={case} tokens={len(ids)} markers={markers}")

    lines: list[str] = []

    # One hook per block, plus the norms and the type addition, named to match the Java trace.
    def hook(name):
        def run(_module, _inputs, output):
            tensor = output[0] if isinstance(output, tuple) else output
            if isinstance(tensor, torch.Tensor):
                lines.append(fingerprint(name, tensor))
        return run

    handles = []
    # The module names differ between laya versions, so discover rather than assume.
    named = dict(model.named_modules())
    encoder_blocks = [n for n in named if n.endswith("layers") or n.endswith("layer")]
    print("# candidate containers:", encoder_blocks[:6], file=sys.stderr)
    for name, module in named.items():
        leaf = name.split(".")[-1]
        if leaf.isdigit() and (".layers." in name or ".layer." in name):
            handles.append(module.register_forward_hook(hook(f"blk{leaf}@{name}")))
        if leaf in {"norm", "final_norm", "embeddings", "tok_norm", "scorer"}:
            handles.append(module.register_forward_hook(hook(leaf)))

    out = model(
        input_ids=torch.tensor([ids], dtype=torch.long),
        attention_mask=torch.ones((1, len(ids)), dtype=torch.long),
        marker_pos=torch.tensor([markers], dtype=torch.long),
        marker_mask=torch.ones((1, len(markers)), dtype=torch.bool),
        qtype=torch.tensor([QTYPES[q["t"]]], dtype=torch.long),
        position_ids=torch.tensor([layout["position_ids"]], dtype=torch.long),
        option_ids=torch.tensor([layout["option_ids"]], dtype=torch.long),
    )
    for handle in handles:
        handle.remove()

    for line in lines:
        print(line)

    # Written BEFORE the logits are touched. The model returns a tuple whose first element is the
    # logits and whose others are the act head's outputs, so an unguarded conversion raises -- and
    # on the first run that exception discarded the whole dump after it had been computed.
    (HERE / f"INTERMEDIATES-{case}.txt").write_text(
        f"# case={case} tokens={len(ids)} markers={markers}\n" + "\n".join(lines) + "\n"
    )

    logits = out[0] if isinstance(out, (tuple, list)) else out
    if isinstance(logits, dict):
        logits = logits.get("logits")
    flat = torch.as_tensor(logits).reshape(-1).tolist()
    print("# raw logits:", [round(float(v), 6) for v in flat])
    with (HERE / f"INTERMEDIATES-{case}.txt").open("a") as handle:
        handle.write("# raw logits: " + repr([round(float(v), 6) for v in flat]) + "\n")
    print(f"# wrote INTERMEDIATES-{case}.txt", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

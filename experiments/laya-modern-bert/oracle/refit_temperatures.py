#!/usr/bin/env python3
"""Refit Laya's per-bucket temperatures on a held-out split, and score the other half.

Gate 8 found Laya accurate (0.7833) and underconfident (ECE 0.1638) because the temperatures it
ships were fitted on some other distribution. Temperature scaling is one scalar per bucket, so this
asks whether refitting repairs it -- without letting the fit see the cases it is judged on.

Pure arithmetic over the logits the Java forward pass already recorded. No model runs here, which
is the point: the logits are fixed, so a temperature cannot change which option is the argmax and
accuracy on the evaluation half is decided before this script starts. The calibration number is the
result.

The split, the >= 10 fit cases rule, and the gate were registered in README.md BEFORE any
temperature was fitted. Read that section before this file.

Usage: python oracle/refit_temperatures.py [report.json]
"""

from __future__ import annotations

import json
import math
import pathlib
import sys
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_REPORT = HERE.parent / "evidence" / "jevbench-120-decision-calibration-v2.json"

# Registered before fitting: a bucket with fewer than this many fit cases keeps the artifact's own
# temperature. Fitting a scalar on six points is noise with a decimal point.
MINIMUM_FIT_CASES = 10
MAXIMUM_CALIBRATION_ERROR = 0.10
CALIBRATION_BINS = 10
# The reference's own clamp, which any temperature we fit must also respect.
TEMPERATURE_MIN, TEMPERATURE_MAX = 0.5, 5.0


def softmax(logits: list[float], temperature: float) -> list[float]:
    scaled = [value / temperature for value in logits]
    top = max(scaled)
    exponentials = [math.exp(value - top) for value in scaled]
    total = sum(exponentials)
    return [value / total for value in exponentials]


def negative_log_likelihood(cases: list[dict], temperature: float) -> float:
    total = 0.0
    for case in cases:
        probabilities = softmax(case["rawLogits"], temperature)
        index = case["expectedIndex"]
        total -= math.log(max(probabilities[index], 1e-12))
    return total / len(cases)


def fit_temperature(cases: list[dict]) -> float:
    """Scalar search for the T minimising NLL of the correct option -- standard temperature scaling.

    A coarse sweep then a refinement, rather than a gradient step, because the objective is
    one-dimensional and smooth and a sweep cannot land in a local minimum it did not visit.
    """
    best, best_loss = TEMPERATURE_MIN, float("inf")
    steps = 450
    for step in range(steps + 1):
        temperature = TEMPERATURE_MIN + (TEMPERATURE_MAX - TEMPERATURE_MIN) * step / steps
        loss = negative_log_likelihood(cases, temperature)
        if loss < best_loss:
            best, best_loss = temperature, loss
    low = max(TEMPERATURE_MIN, best - 0.02)
    high = min(TEMPERATURE_MAX, best + 0.02)
    for step in range(201):
        temperature = low + (high - low) * step / 200
        loss = negative_log_likelihood(cases, temperature)
        if loss < best_loss:
            best, best_loss = temperature, loss
    return best


def calibration_error(rows: list[tuple[float, bool]]) -> float:
    if not rows:
        return float("nan")
    confidence_sum = [0.0] * CALIBRATION_BINS
    correct_sum = [0.0] * CALIBRATION_BINS
    counts = [0] * CALIBRATION_BINS
    for confidence, correct in rows:
        index = min(CALIBRATION_BINS - 1, int(confidence * CALIBRATION_BINS))
        confidence_sum[index] += confidence
        correct_sum[index] += 1.0 if correct else 0.0
        counts[index] += 1
    error = 0.0
    for index in range(CALIBRATION_BINS):
        if not counts[index]:
            continue
        mean_confidence = confidence_sum[index] / counts[index]
        mean_accuracy = correct_sum[index] / counts[index]
        error += (counts[index] / len(rows)) * abs(mean_confidence - mean_accuracy)
    return error


def main() -> int:
    report_path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_REPORT
    report = json.loads(report_path.read_text())
    cases = report["cases_detail"]
    if "rawLogits" not in cases[0]:
        print("this report has no rawLogits; re-run the capture", file=sys.stderr)
        return 2

    # The registered split: sort by id, even indices fit, odd indices evaluate.
    cases.sort(key=lambda case: case["id"])
    fit = [case for index, case in enumerate(cases) if index % 2 == 0]
    evaluate = [case for index, case in enumerate(cases) if index % 2 == 1]

    by_bucket_fit: dict[str, list[dict]] = defaultdict(list)
    for case in fit:
        by_bucket_fit[case["bucket"]].append(case)

    shipped = {case["bucket"]: case["temperature"] for case in cases}
    refitted: dict[str, float] = {}
    decisions: list[str] = []
    for bucket, bucket_cases in sorted(by_bucket_fit.items()):
        if len(bucket_cases) < MINIMUM_FIT_CASES:
            refitted[bucket] = shipped[bucket]
            decisions.append(
                f"  {bucket:<14} n={len(bucket_cases):<3} KEPT shipped T={shipped[bucket]:.4f}"
                f"  (under the {MINIMUM_FIT_CASES}-case floor)"
            )
            continue
        temperature = fit_temperature(bucket_cases)
        refitted[bucket] = temperature
        decisions.append(
            f"  {bucket:<14} n={len(bucket_cases):<3} refit T={temperature:.4f}"
            f"  (shipped {shipped[bucket]:.4f})"
        )

    print("Per-bucket temperatures, fit on the 60 even-indexed cases:")
    for line in decisions:
        print(line)

    print("\nEvaluation half (60 odd-indexed cases), which the fit never saw:")
    results = {}
    for label, temperatures in (("shipped", shipped), ("refitted", refitted)):
        rows: list[tuple[float, bool]] = []
        correct = 0
        confidence_total = 0.0
        for case in evaluate:
            probabilities = softmax(case["rawLogits"], temperatures[case["bucket"]])
            chosen = max(range(len(probabilities)), key=probabilities.__getitem__)
            is_correct = chosen == case["expectedIndex"]
            correct += is_correct
            confidence = probabilities[chosen]
            confidence_total += confidence
            rows.append((confidence, is_correct))
        accuracy = correct / len(evaluate)
        ece = calibration_error(rows)
        results[label] = (accuracy, ece, confidence_total / len(evaluate))
        print(
            f"  {label:<9} accuracy={accuracy:.4f}  meanConfidence={confidence_total/len(evaluate):.4f}"
            f"  ECE={ece:.4f}  {'PASS' if ece <= MAXIMUM_CALIBRATION_ERROR else 'FAIL'}"
        )

    shipped_accuracy = results["shipped"][0]
    refitted_accuracy = results["refitted"][0]
    print(
        "\nAccuracy unchanged by construction: "
        f"{shipped_accuracy:.4f} -> {refitted_accuracy:.4f}. "
        "A temperature divides every logit by the same scalar, so it cannot move an argmax; any "
        "difference here would be a bug in this script."
    )
    out = {
        "split": "sorted by id; even indices fit, odd indices evaluate",
        "minimumFitCases": MINIMUM_FIT_CASES,
        "shippedTemperatures": shipped,
        "refittedTemperatures": refitted,
        "evaluation": {
            label: {"accuracy": value[0], "calibrationError": value[1], "meanConfidence": value[2]}
            for label, value in results.items()
        },
        "qualifiedOnEvaluationHalf": results["refitted"][1] <= MAXIMUM_CALIBRATION_ERROR,
    }
    destination = report_path.parent / "jevbench-temperature-refit.json"
    destination.write_text(json.dumps(out, indent=2) + "\n")
    print(f"\nwrote {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

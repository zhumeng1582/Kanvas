#!/usr/bin/env python3
"""Fail CI when Kanvas Macrobenchmark frame percentiles miss a device profile."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path


SCENARIOS = {
    "panOnlyWithHundredThousandCandles": "pan",
    "pinchOnlyWithHundredThousandCandles": "pinch",
    "crossOnlyWithHundredThousandCandles": "cross",
}
BLOCK = re.compile(
    r"KanvasMacrobenchmark_(?P<method>" + "|".join(SCENARIOS) + r")(?P<body>.*?)(?:Traces:)",
    re.DOTALL,
)
CPU = re.compile(
    r"frameDurationCpuMs\s+P50\s+-?[\d,.]+\s*,\s*P90\s+-?[\d,.]+\s*,\s*"
    r"P95\s+(?P<p95>-?[\d,.]+)\s*,\s*P99\s+(?P<p99>-?[\d,.]+)",
)
OVERRUN = re.compile(
    r"frameOverrunMs\s+P50\s+-?[\d,.]+\s*,\s*P90\s+-?[\d,.]+\s*,\s*"
    r"P95\s+(?P<p95>-?[\d,.]+)\s*,\s*P99\s+(?P<p99>-?[\d,.]+)",
)


@dataclass(frozen=True)
class FrameResult:
    cpu_p95: float
    cpu_p99: float
    overrun_p95: float
    overrun_p99: float
    iterations: int = 0


def number(value: str) -> float:
    return float(value.replace(",", ""))


def parse_text(text: str) -> dict[str, FrameResult]:
    parsed: dict[str, FrameResult] = {}
    for match in BLOCK.finditer(text):
        body = match.group("body")
        cpu = CPU.search(body)
        overrun = OVERRUN.search(body)
        if cpu is None or overrun is None:
            continue
        parsed[SCENARIOS[match.group("method")]] = FrameResult(
            cpu_p95=number(cpu.group("p95")),
            cpu_p99=number(cpu.group("p99")),
            overrun_p95=number(overrun.group("p95")),
            overrun_p99=number(overrun.group("p99")),
        )
    return parsed


def parse_json(text: str) -> dict[str, FrameResult]:
    parsed: dict[str, FrameResult] = {}
    for benchmark in json.loads(text)["benchmarks"]:
        scenario = SCENARIOS.get(benchmark["name"])
        if scenario is None:
            continue
        metrics = benchmark["sampledMetrics"]
        cpu = metrics["frameDurationCpuMs"]
        overrun = metrics["frameOverrunMs"]
        values = [
            float(metric[percentile])
            for metric in (cpu, overrun)
            for percentile in ("P95", "P99")
        ]
        if not all(math.isfinite(value) for value in values):
            raise ValueError(f"Non-finite frame metrics for {scenario}")
        parsed[scenario] = FrameResult(*values, iterations=int(benchmark.get("repeatIterations", 0)))
    return parsed


def result_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    # The Studio summary rounds to one decimal place: 8.34ms becomes 8.3ms,
    # which would incorrectly pass the 8.33ms gate. Prefer raw JSON exclusively
    # when available; do not fill missing scenarios from older text summaries.
    candidates = list(path.rglob("*-benchmarkData.json"))
    if not candidates:
        candidates = list(path.rglob("test-result.textproto")) + list(path.rglob("TEST-*.xml"))
    return sorted(candidates, key=lambda candidate: candidate.stat().st_mtime)


def load_results(path: Path) -> dict[str, FrameResult]:
    results: dict[str, FrameResult] = {}
    for candidate in result_files(path):
        text = candidate.read_text(encoding="utf-8", errors="replace")
        results.update(parse_json(text) if candidate.suffix == ".json" else parse_text(text))
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument(
        "--matrix",
        type=Path,
        default=Path("benchmark/thresholds/120hz-device-matrix.json"),
    )
    parser.add_argument("--profile", default="strict-120hz")
    args = parser.parse_args()

    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    try:
        profile = matrix["profiles"][args.profile]
    except KeyError as error:
        raise SystemExit(f"Unknown threshold profile: {args.profile}") from error
    limits = profile["thresholds"]
    if any(path.suffix != ".json" for path in result_files(args.results)):
        print(
            "Strict acceptance requires raw *-benchmarkData.json; rounded text summaries "
            "cannot certify the frame-time boundary.",
            file=sys.stderr,
        )
        return 2
    results = load_results(args.results)
    missing = [name for name in profile["requiredScenarios"] if name not in results]
    if missing:
        print(f"Missing Macrobenchmark scenarios: {', '.join(missing)}", file=sys.stderr)
        return 2
    minimum_iterations = profile.get("minimumIterations", 8)
    insufficient = [name for name, result in results.items() if result.iterations < minimum_iterations]
    if insufficient:
        print(
            f"At least {minimum_iterations} iterations required: {', '.join(insufficient)}",
            file=sys.stderr,
        )
        return 2

    checks = (
        ("CPU P95", "cpu_p95", "frameDurationCpuMsP95"),
        ("CPU P99", "cpu_p99", "frameDurationCpuMsP99"),
        ("Overrun P95", "overrun_p95", "frameOverrunMsP95"),
        ("Overrun P99", "overrun_p99", "frameOverrunMsP99"),
    )
    failed = False
    print("| Scenario | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | Result |")
    print("| --- | ---: | ---: | ---: | ---: | --- |")
    for scenario in profile["requiredScenarios"]:
        result = results[scenario]
        failures = [
            label
            for label, result_field, limit_field in checks
            if getattr(result, result_field) > float(limits[limit_field])
        ]
        failed = failed or bool(failures)
        status = "FAIL: " + ", ".join(failures) if failures else "PASS"
        print(
            f"| {scenario} | {result.cpu_p95:.3f}ms | {result.cpu_p99:.3f}ms | "
            f"{result.overrun_p95:.3f}ms | {result.overrun_p99:.3f}ms | {status} |"
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

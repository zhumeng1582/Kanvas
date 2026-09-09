import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "check_macrobenchmark_thresholds.py"
SPEC = importlib.util.spec_from_file_location("check_macrobenchmark_thresholds", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MacrobenchmarkThresholdParserTest(unittest.TestCase):
    def raw_result(self, cpu_p99=8.34):
        return json.dumps({"benchmarks": [{
            "name": "panOnlyWithHundredThousandCandles",
            "repeatIterations": 8,
            "sampledMetrics": {
                "frameDurationCpuMs": {"P95": 8.331, "P99": cpu_p99},
                "frameOverrunMs": {"P95": -0.01, "P99": 0.004},
            },
        }]})

    def test_preserves_precision_at_gate_boundary(self):
        parsed = MODULE.parse_json(self.raw_result())
        self.assertEqual(MODULE.FrameResult(8.331, 8.34, -0.01, 0.004, iterations=8), parsed["pan"])
        self.assertGreater(parsed["pan"].cpu_p95, 8.33)
        self.assertGreater(parsed["pan"].overrun_p99, 0)

    def test_rejects_non_finite_metrics(self):
        with self.assertRaises(ValueError):
            MODULE.parse_json(self.raw_result(float("nan")))

    def test_raw_results_do_not_merge_stale_text_scenarios(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app-benchmarkData.json").write_text(self.raw_result())
            (root / "TEST-stale.xml").write_text(
                "KanvasMacrobenchmark_crossOnlyWithHundredThousandCandles\n"
                "frameDurationCpuMs P50 1.0, P90 2.0, P95 3.0, P99 4.0\n"
                "frameOverrunMs P50 -4.0, P90 -3.0, P95 -2.0, P99 -1.0\nTraces:"
            )
            parsed = MODULE.load_results(root)
        self.assertEqual(["pan"], list(parsed))
        self.assertEqual(8.34, parsed["pan"].cpu_p99)

    def test_cli_refuses_rounded_text_for_strict_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-result.xml"
            path.write_text("rounded summary")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--results", str(path), "--matrix",
                 str(SCRIPT.parents[1] / "benchmark/thresholds/120hz-device-matrix.json")],
                capture_output=True,
                text=True,
            )
        self.assertEqual(2, result.returncode)
        self.assertIn("requires raw", result.stderr)

    def test_cli_does_not_accept_short_diagnostic_runs(self):
        benchmark = json.loads(self.raw_result())["benchmarks"][0]
        data = {"benchmarks": [
            dict(benchmark, name=name, repeatIterations=1) for name in MODULE.SCENARIOS
        ]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-benchmarkData.json"
            path.write_text(json.dumps(data))
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--results", str(path), "--matrix",
                 str(SCRIPT.parents[1] / "benchmark/thresholds/120hz-device-matrix.json")],
                capture_output=True,
                text=True,
            )
        self.assertEqual(2, result.returncode)
        self.assertIn("At least 8 iterations", result.stderr)

    def test_parses_escaped_textproto_summary(self):
        text = (
            'value: "KanvasMacrobenchmark_panOnlyWithHundredThousandCandles\\n'
            'frameDurationCpuMs    P50 4.0, P90 6.0, P95 7.1, P99 8.2\\n'
            'frameOverrunMs        P50 -4.0, P90 -2.0, P95 -1.2, P99 -0.1\\n'
            'Traces: Iteration [0]"'
        )

        parsed = MODULE.parse_text(text)

        self.assertEqual(
            MODULE.FrameResult(7.1, 8.2, -1.2, -0.1),
            parsed["pan"],
        )


if __name__ == "__main__":
    unittest.main()

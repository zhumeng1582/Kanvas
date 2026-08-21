import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "check_macrobenchmark_thresholds.py"
SPEC = importlib.util.spec_from_file_location("check_macrobenchmark_thresholds", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MacrobenchmarkThresholdParserTest(unittest.TestCase):
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

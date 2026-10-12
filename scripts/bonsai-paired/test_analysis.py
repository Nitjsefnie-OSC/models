"""Negative checks: incomplete observations and imprecise/regressing samples cannot pass."""
import importlib.util
import math
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('paired_analysis', Path(__file__).with_name('analyze.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class GateTests(unittest.TestCase):
    def test_regression_fails_even_when_precise(self):
        result = module.bootstrap([[math.log(1.05)] * 5] * 2, 1.01)
        self.assertFalse(result['pass'])
        self.assertEqual('REGRESSION', result['status'])

    def test_wide_interval_cannot_pass(self):
        result = module.bootstrap([[math.log(x) for x in [.6, .8, 1., 1.2, 1.4]]] * 2, 1.01)
        self.assertFalse(result['pass'])
        self.assertFalse(result['precisionTargetMet'])

    def test_precise_improvement_passes(self):
        result = module.bootstrap([[math.log(.9)] * 5] * 2, 1.)
        self.assertTrue(result['pass'])

    def test_missing_shard_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(FileNotFoundError):
                module.analyze(Path(folder), 'arm', 'bonsai2')


if __name__ == '__main__':
    unittest.main()

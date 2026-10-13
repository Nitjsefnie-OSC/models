import importlib.util
import itertools
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('analysis',Path(__file__).with_name('analyze.py'))
analysis=importlib.util.module_from_spec(spec);spec.loader.exec_module(analysis)

class AnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.root.joinpath('route-provenance.json').write_text(json.dumps({'protocol':analysis.CONFIG}))
        variants=['hybrid','native','java']
        for arm in variants:
            cohort=[{'kind':'request','id':str(i),'inputTokens':[1],'fullLogitSha256':['abc']*9,'greedyTokens':[1]*8,'prediction':'A'} for i in range(148)]
            self.root.joinpath(f'cohort-{arm}.stdout').write_text('\n'.join(map(json.dumps,cohort)))
            self.root.joinpath(f'cohort-{arm}.execution.json').write_text('{"exitCode":0,"timedOut":false}')
        for pair in range(12):
            order=list(list(itertools.permutations(variants))[pair%6])
            self.root.joinpath(f'triplet-{pair}.complete.json').write_text(json.dumps({'pair':pair,'order':order}))
            for arm in variants:
                prefix=self.root/f'timing-{pair}-{arm}'
                cost=110 if arm=='hybrid' else 100
                rows=[{'kind':'load','nanos':100}]
                for repeat in range(-32,3):
                    rows.append({'kind':'request','id':'same','repeat':repeat,'inputTokens':[1],
                                 'fullLogitSha256':['abc']*9,'greedyTokens':[1]*8,'prediction':'A',
                                 'prefillNanos':cost,'decodeNanos':cost,'modelNanos':cost,'requestNanos':cost,'javaAllocatedBytes':100})
                prefix.with_suffix('.stdout').write_text('\n'.join(map(json.dumps,rows)))
                prefix.with_suffix('.stderr').write_text('Maximum resident set size (kbytes): 100\n')
                prefix.with_suffix('.execution.json').write_text('{"exitCode":0}')
    def tearDown(self):self.temp.cleanup()
    def test_regression_is_retained(self):
        value=analysis.analyze(self.root)
        metric=value['contrasts']['hybrid/native']['prefillNanos']
        self.assertFalse(metric['pass']);self.assertEqual(metric['status'],'REGRESSION')
    def test_missing_triplet_rejected(self):
        self.root.joinpath('triplet-11.complete.json').unlink()
        with self.assertRaises(FileNotFoundError):analysis.analyze(self.root)
    def test_numerical_drift_rejected(self):
        path=self.root/'timing-0-hybrid.stdout';path.write_text(path.read_text().replace('abc','xyz'))
        with self.assertRaisesRegex(AssertionError,'numerical difference'):analysis.analyze(self.root)
    def test_missing_warm_request_rejected(self):
        path=self.root/'timing-0-hybrid.stdout';path.write_text('\n'.join(path.read_text().splitlines()[:-1]))
        with self.assertRaises(AssertionError):analysis.analyze(self.root)
    def test_arm_order_is_checked(self):
        path=self.root/'triplet-0.complete.json';path.write_text('{"pair":0,"order":["java","native","hybrid"]}')
        with self.assertRaises(AssertionError):analysis.analyze(self.root)

if __name__=='__main__':unittest.main()

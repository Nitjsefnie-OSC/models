import importlib.util
import pathlib
import unittest


spec = importlib.util.spec_from_file_location(
    "portable_paths", pathlib.Path(__file__).with_name("check-portable-paths.py")
)
portable_paths = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portable_paths)


class PortablePathsTest(unittest.TestCase):
    def test_rejects_the_path_that_broke_windows_checkout(self):
        path = (
            "benchmark-results/2026-10-12-bonsai-native-hotfix/"
            "serial-allocation-expanded-github-38324642561/"
            "bonsai-upstream-ubuntu-24.04-arm-jdk-25.0.3/test-results/bonsai2NativeSlowTest/"
            "TEST-com.integrallis.models.backend.nativekernel.Bonsai2NativeFreshSessionSlowTest.xml"
        )
        self.assertEqual([path], portable_paths.oversized_paths([path]))

    def test_accepts_the_boundary_and_counts_utf16_units(self):
        self.assertEqual([], portable_paths.oversized_paths(["a" * 230]))
        self.assertEqual(["a" * 231], portable_paths.oversized_paths(["a" * 231]))
        unicode_path = "a" * 229 + "\U0001f600"
        self.assertEqual([unicode_path], portable_paths.oversized_paths([unicode_path]))

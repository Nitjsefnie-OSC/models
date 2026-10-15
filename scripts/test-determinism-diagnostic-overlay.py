"""Exercise matrix selection, overlay precedence, provenance and failure collection without inference."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("run-purejava-determinism-matrix.sh")
VARIANTS = ["panama-default", "panama-serial", "panama-no-fma", "panama-128", "scalar"]
MOCK = '''#!/usr/bin/env python3
import json, os, pathlib, sys
if "-XshowSettings:properties" in sys.argv:
    print("java.specification.version = 25", file=sys.stderr)
    raise SystemExit(0)
with open(os.environ["MOCK_LOG"], "a") as f:
    f.write(json.dumps({"executable": pathlib.Path(sys.argv[0]).name, "args": sys.argv[1:], "javaOpts": os.environ.get("JAVA_OPTS")}) + "\\n")
name = pathlib.Path(sys.argv[sys.argv.index("--output") + 1]).stem
raise SystemExit(1 if name in os.environ.get("MOCK_FAILURES", "").split(",") else 0)
'''


class DiagnosticOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(SCRIPT, self.root / "scripts" / SCRIPT.name)
        self.dist = self.root / "models-bench/build/install/models-bench"
        (self.dist / "bin").mkdir(parents=True)
        (self.dist / "lib").mkdir()
        (self.dist / "lib/vectors-core-released.jar").write_bytes(b"released")
        self.bin = self.root / "mock-bin"
        self.bin.mkdir()
        for path, content in [
            (self.bin / "java", MOCK),
            (self.dist / "bin/models-bench", MOCK),
            (self.root / "gradlew", "#!/bin/sh\nexit 0\n"),
            (self.bin / "git", "#!/bin/sh\nprintf '%040d\\n' 1\n"),
        ]:
            path.write_text(content)
            path.chmod(0o755)
        self.model = self.root / "fixture.gguf"
        self.model.write_bytes(b"fixture")
        self.jar = self.root / "candidate core.jar"
        self.jar.write_bytes(b"candidate")
        self.log = self.root / "calls.jsonl"
        self.output = self.root / "report"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        MOCK_LOG=str(self.log), AUDIT_PROMPT="fixture prompt")
        for key in ["AUDIT_VARIANT", "AUDIT_VECTORS_CORE_JAR", "AUDIT_VECTORS_CORE_REVISION"]:
            self.env.pop(key, None)

    def run_script(self, **extra):
        result = subprocess.run(["bash", str(self.root / "scripts" / SCRIPT.name),
                                 str(self.model), "fixture", str(self.output)],
                                env=dict(self.env, **extra), capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, rows

    def test_default_keeps_all_five_and_collects_failures(self):
        result, rows = self.run_script(MOCK_FAILURES="panama-default,panama-no-fma")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(rows), 5)
        self.assertTrue(all(row["executable"] == "models-bench" for row in rows))
        self.assertIn("2 determinism audit(s) failed", result.stderr)
        self.assertFalse((self.output / "diagnostic-core.json").exists())

    def test_overlay_precedes_distribution_and_preserves_each_configuration(self):
        revision = "a" * 40
        result, rows = self.run_script(AUDIT_VECTORS_CORE_JAR=str(self.jar),
                                      AUDIT_VECTORS_CORE_REVISION=revision)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(rows), 5)
        for row in rows:
            args = row["args"]
            self.assertEqual(args[args.index("-cp") + 1], str(self.jar.resolve()) + ":" + str(self.dist / "lib") + "/*")
            self.assertIn("--add-modules", args)
            self.assertIn("--enable-native-access=ALL-UNNAMED", args)
            self.assertIn("-XX:NativeMemoryTracking=summary", args)
            for flag in row["javaOpts"].split():
                self.assertIn(flag, args)
        metadata = json.loads((self.output / "diagnostic-core.json").read_text())
        self.assertEqual(metadata["vectorsCoreRevision"], revision)
        self.assertEqual(metadata["vectorsCoreSha256"], hashlib.sha256(b"candidate").hexdigest())
        self.assertEqual(len(metadata["distributionCoreJars"]), 1)

    def test_each_selected_variant_runs_once(self):
        for variant in VARIANTS:
            with self.subTest(variant=variant):
                self.log.unlink(missing_ok=True)
                result, rows = self.run_script(AUDIT_VARIANT=variant)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(rows), 1)
                self.assertIn(str(self.output / (variant + ".json")), rows[0]["args"])

    def test_invalid_inputs_fail_before_benchmark(self):
        for extra in [{"AUDIT_VARIANT": "unknown"},
                      {"AUDIT_VECTORS_CORE_JAR": str(self.jar), "AUDIT_VECTORS_CORE_REVISION": "main"},
                      {"AUDIT_VECTORS_CORE_JAR": str(self.root / "missing.jar"), "AUDIT_VECTORS_CORE_REVISION": "a" * 40}]:
            with self.subTest(extra=extra):
                result, rows = self.run_script(**extra)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(rows, [])


if __name__ == "__main__":
    unittest.main()

"""Regression checks for evidence directory boundaries, including external archive names."""
import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "evidence_references", pathlib.Path(__file__).with_name("check-evidence-references.py"))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class EvidenceReferencesTest(unittest.TestCase):
    def check_document(self, text, existing=()):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "README.md").write_text(text)
            for relative in existing:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("retained evidence")
            with contextlib.redirect_stdout(io.StringIO()):
                return CHECK.main(["--root", str(root)])

    def test_archive_name_is_not_a_repository_experiments_directory(self):
        self.assertEqual(0, self.check_document(
            "Archived at `audit-artifacts/rejected-experiments/vectors/old-run`."))

    def test_missing_local_evidence_still_fails(self):
        for path in ("experiments/missing.json", "benchmark-results/missing.json",
                     "../experiments/missing.json", "./benchmark-results/missing.json"):
            with self.subTest(path=path):
                self.assertEqual(1, self.check_document(f"Evidence: `{path}`."))

    def test_external_archive_does_not_hide_an_adjacent_missing_local_citation(self):
        self.assertEqual(1, self.check_document(
            "`audit-artifacts/rejected-experiments/old` and `experiments/missing.json`."))

    def test_existing_evidence_resolves(self):
        self.assertEqual(0, self.check_document(
            "[Result](benchmark-results/retained.json)", ["benchmark-results/retained.json"]))


if __name__ == "__main__":
    unittest.main()

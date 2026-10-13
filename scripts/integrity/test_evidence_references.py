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

    def test_module_prefix_is_preserved(self):
        self.assertEqual(0, self.check_document(
            "[Result](models-bench/benchmark-results/retained.json)",
            ["models-bench/benchmark-results/retained.json"]))
        self.assertEqual(1, self.check_document(
            "[Result](models-bench/benchmark-results/retained.json)",
            ["benchmark-results/retained.json"]))

    def test_relative_module_link_resolves_from_document(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            document = root / "benchmark-results/README.md"
            document.parent.mkdir()
            document.write_text(
                "[Result](../models-bench/benchmark-results/retained.json)")
            report = root / "models-bench/benchmark-results/retained.json"
            report.parent.mkdir(parents=True)
            report.write_text("retained evidence")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, CHECK.main(["--root", str(root)]))

    def test_remote_report_is_outside_local_file_scope(self):
        self.assertEqual(0, self.check_document(
            "[Report](https://example.com/project/benchmark-results/remote.json)"))

    def test_external_snapshot_is_not_a_live_repository_citation(self):
        self.assertEqual(0, self.check_document(
            "`audit-artifacts/retired/models/benchmark-results/old/report.json`"))
        self.assertEqual(1, self.check_document(
            "`audit-artifacts/retired/models/benchmark-results/old/report.json` and "
            "`models-bench/benchmark-results/missing.json`"))

    def test_shell_variable_is_not_interpreted_as_a_literal_directory(self):
        self.assertEqual(0, self.check_document(
            '`$PWD/benchmark-results/future-output.json`'))


if __name__ == "__main__":
    unittest.main()

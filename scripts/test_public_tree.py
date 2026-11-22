import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check-public-tree.py")
SPEC = importlib.util.spec_from_file_location("public_tree", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
RULES = CHECK.MARKER + "\nAGENTS.md\n.private/\n/docs/internal.md\n"


class PublicTreeTests(unittest.TestCase):
    def test_nested_and_case_variants(self):
        paths = ["AGENTS.md", "app/agents.md", "app/AGENTS.override.md",
                 ".claude/settings.json", "app/.private/notes.txt"]
        self.assertEqual(CHECK.find_private_paths(paths, RULES), sorted(paths))

    def test_exact_paths_preserve_public_docs(self):
        paths = ["docs/internal.md", "app/docs/internal.md", "docs/building.md",
                 "docs/release-notes.md", "app/src/AgentState.kt"]
        self.assertEqual(CHECK.find_private_paths(paths, RULES), ["docs/internal.md"])

    def test_missing_rules_fail_closed(self):
        with self.assertRaises(ValueError):
            CHECK.find_private_paths([], "build/\n")

    def test_index_and_commit_do_not_read_unstaged_content(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)

            def git(*args):
                return subprocess.check_output(["git", "-C", folder, *args],
                                               stderr=subprocess.DEVNULL)

            def check(*args):
                return subprocess.run([os.sys.executable, str(SCRIPT), *args],
                                      cwd=folder, capture_output=True)

            git("init", "-q")
            (root / ".gitignore").write_text(RULES, encoding="utf-8")
            (root / "README.md").write_text("Public guide\n", encoding="utf-8")
            git("add", ".gitignore", "README.md")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "core.hooksPath=/dev/null", "commit", "-qm", "Public files")
            (root / "AGENTS.md").write_text("Private instructions\n", encoding="utf-8")
            self.assertEqual(check().returncode, 0)
            git("add", "-f", "AGENTS.md")
            result = check()
            self.assertEqual(result.returncode, 1)
            self.assertIn(b"AGENTS.md", result.stderr)
            self.assertEqual(check("--revision", "HEAD").returncode, 0)
            (root / "AGENTS.md").unlink()
            self.assertEqual(check().returncode, 1)


if __name__ == "__main__":
    unittest.main()

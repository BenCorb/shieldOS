"""shieldOS fork checks: the shared Shield theme is valid and nothing personal is set up to be tracked.

The fork keeps everything about one person's Shield in local/ (gitignored). These tests make sure the
shared theme only uses values the launcher keeps, and that local/ and CLAUDE.local.md stay ignored.
"""
import json
import subprocess
import unittest
from pathlib import Path

try:
    import test_shipped_config_values as checker
except ImportError:  # run as tests.test_shieldos_fork from the repo root
    from tests import test_shipped_config_values as checker

ROOT = Path(__file__).resolve().parent.parent
SHIELDOS = ROOT / "shieldos"
THEMES = [SHIELDOS / "theme.json", SHIELDOS / "local.example" / "theme.json"]


class ShieldosThemeTest(unittest.TestCase):
    def setUp(self):
        self.kinds = checker.readers(checker.CONFIG_JAVA.read_text(encoding="utf-8"))

    def test_shared_themes_use_values_the_launcher_keeps(self):
        for path in THEMES:
            with self.subTest(theme=str(path.relative_to(ROOT))):
                config = {k: v for k, v in json.loads(path.read_text(encoding="utf-8")).items()
                          if not k.startswith("_")}
                self.assertEqual(checker.problems(config, self.kinds), [])

    def test_shared_theme_turns_on_the_fork_look(self):
        config = json.loads((SHIELDOS / "theme.json").read_text(encoding="utf-8"))
        for key in ("tvos_focus", "tvos_home", "liquid_glass", "dock_glass"):
            with self.subTest(key=key):
                self.assertIs(config.get(key), True)


class PersonalDataStaysLocalTest(unittest.TestCase):
    def test_local_files_are_ignored(self):
        try:
            for path in ("local/shield.env", "local/theme.json", "CLAUDE.local.md"):
                with self.subTest(path=path):
                    result = subprocess.run(["git", "check-ignore", "-q", path], cwd=ROOT)
                    self.assertEqual(result.returncode, 0, f"{path} would be tracked: check .gitignore")
        except FileNotFoundError:
            self.skipTest("git is not installed")


if __name__ == "__main__":
    unittest.main()

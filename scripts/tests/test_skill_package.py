import contextlib
import importlib.util
import io
from pathlib import Path
import shutil
import tempfile
import unittest
from zipfile import ZipFile


class SkillPackageTests(unittest.TestCase):
    def test_digest_survives_windows_line_endings_and_private_files_are_excluded(self):
        script = Path(__file__).resolve().parents[1] / "package-telegram-skill.py"
        spec = importlib.util.spec_from_file_location("skill_package", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(prefix="telegram-skill-package-test-") as folder:
            root = Path(folder).resolve()
            self.assertTrue(root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
            copied = root / "skill"
            shutil.copytree(module.SKILL, copied)
            module.SKILL = copied
            first, second = root / "lf.zip", root / "crlf.zip"
            with contextlib.redirect_stdout(io.StringIO()):
                module.package(first)
                for name in module.FILES:
                    p = copied / name
                    p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
                (copied / "credentials.json").write_text('{"secret":"synthetic-private-data"}')
                module.package(second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with ZipFile(second) as archive:
                self.assertNotIn("credentials.json", archive.namelist())
                self.assertEqual(list(module.FILES), archive.namelist())
                self.assertTrue(all(entry.create_system == 3 for entry in archive.infolist()))

"""Release-policy regressions: tags, ancestry, immutable coordinates and version ordering."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import subprocess
import tempfile
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePolicyTest(unittest.TestCase):
    def test_version_code_increases_across_patch_minor_and_major_boundaries(self):
        versions = ["0.0.1", "0.999.999", "1.0.0", "1.0.1", "1.999.999", "2.0.0", "2099.999.999"]
        codes = [release.parse_version(v)[1] for v in versions]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertLessEqual(codes[-1], 2_100_000_000)
        self.assertEqual(release.parse_version("1.0.1")[1], 1_000_001)

    def test_rejects_invalid_or_unencodable_versions(self):
        for version in ["v1.2.3", "01.2.3", "1.02.3", "1.2", "1.2.3-beta", "1.2.3+4", "1.2.3\n", "1.2.3;echo x", "0.0.0", "2100.0.0", "1.1000.0", "1.0.1000"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.parse_version(version)

    def git_fixture(self, *args):
        if args == ("rev-parse", "HEAD"):
            return "same-commit"
        if args[0] == "rev-parse":
            return "same-commit"
        return "v1.0.0\nv1.0.1\npreview"

    def test_real_git_history_requires_the_tag_to_be_on_main(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], text=True, stderr=subprocess.DEVNULL).strip()
            git("init", "--initial-branch=main")
            git("-c", "user.name=CI", "-c", "user.email=ci@example.invalid", "commit", "--allow-empty", "-m", "main")
            git("update-ref", "refs/remotes/origin/main", "HEAD")
            git("tag", "v1.0.1")
            with patch.object(release, "ROOT", root):
                self.assertEqual(release.validate_tag("v1.0.1")[0], "1.0.1")
                git("-c", "user.name=CI", "-c", "user.email=ci@example.invalid", "commit", "--allow-empty", "-m", "unmerged")
                git("-c", "user.name=CI", "-c", "user.email=ci@example.invalid", "tag", "-a", "v1.0.2", "-m", "annotated release")
                with self.assertRaisesRegex(ValueError, "origin/main"):
                    release.validate_tag("v1.0.2")
                git("update-ref", "refs/remotes/origin/main", "HEAD")
                self.assertEqual(release.validate_tag("v1.0.2")[0], "1.0.2")

    @patch.object(release, "git")
    @patch.object(release.subprocess, "run")
    def test_tag_must_be_on_main_and_match_the_checkout(self, run, git):
        git.side_effect = self.git_fixture
        run.return_value = SimpleNamespace(returncode=0)
        self.assertEqual(release.validate_tag("v1.0.1"), ("1.0.1", 1_000_001))
        run.return_value = SimpleNamespace(returncode=1)
        with self.assertRaisesRegex(ValueError, "origin/main"):
            release.validate_tag("v1.0.1")
        run.return_value = SimpleNamespace(returncode=0)
        git.side_effect = ["head", "different-tag-commit"]
        with self.assertRaisesRegex(ValueError, "match"):
            release.validate_tag("v1.0.1")

    @patch.object(release, "git")
    @patch.object(release.subprocess, "run", return_value=SimpleNamespace(returncode=0))
    def test_rejects_older_main_tag(self, run, git):
        git.side_effect = ["same", "same", "v2.0.0\nv1.0.1"]
        with self.assertRaisesRegex(ValueError, "newer"):
            release.validate_tag("v1.0.1")

    @patch.object(release, "fetch")
    def test_checks_every_artifact_for_preexisting_coordinates(self, fetch):
        fetch.side_effect = lambda url: b"existing" if "/directstream-ui/1.0.1/" in url else None
        with self.assertRaisesRegex(ValueError, "already public"):
            release.require_unpublished("io.github.orthoepiccrown0", "1.0.1")

    @patch.object(release, "fetch")
    def test_metadata_prevents_version_regression_after_history_rewrite(self, fetch):
        fetch.side_effect = lambda url: b"<metadata><versioning><versions><version>2.0.0</version></versions></versioning></metadata>" if url.endswith("maven-metadata.xml") else None
        with self.assertRaisesRegex(ValueError, "newer"):
            release.require_unpublished("io.github.orthoepiccrown0", "1.0.1")

    @patch.object(release, "fetch", return_value=None)
    def test_accepts_new_coordinates_and_bounds_publication_wait(self, fetch):
        release.require_unpublished("io.github.orthoepiccrown0", "1.0.1")
        with self.assertRaisesRegex(TimeoutError, "do not re-upload"):
            release.wait_for_publication("io.github.orthoepiccrown0", "1.0.1", 0)

    @patch.object(release.urllib.request, "urlopen")
    def test_only_404_means_an_unpublished_version(self, urlopen):
        for code in [401, 403, 429, 500]:
            with self.subTest(code=code):
                urlopen.side_effect = urllib.error.HTTPError("https://example.test", code, "error", {}, None)
                with self.assertRaises(RuntimeError):
                    release.fetch("https://example.test")
        urlopen.side_effect = urllib.error.HTTPError("https://example.test", 404, "missing", {}, None)
        self.assertIsNone(release.fetch("https://example.test"))


if __name__ == "__main__":
    unittest.main()

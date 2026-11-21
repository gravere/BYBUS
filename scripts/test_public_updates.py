import copy
import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("public_updates", Path(__file__).with_name("sync-public-updates.py"))
updates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updates)


class PublicUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 11, 21, tzinfo=dt.timezone.utc)
        tag = "2026111901"
        name = f"LimbusLocalize_{tag}.7z"
        self.release = dict(tag_name=tag, published_at="2026-11-19T09:43:59Z", draft=False, prerelease=False,
            html_url=f"https://github.com/{updates.UPSTREAM}/releases/tag/{tag}",
            author={"login": "not-for-feed"}, body="do not copy this", assets=[dict(name=name, state="uploaded",
                size=123456, digest="sha256:" + "a"*64,
                browser_download_url=f"https://github.com/{updates.UPSTREAM}/releases/download/{tag}/{name}")])

    def test_whitelist_excludes_release_author_and_body(self):
        feed = updates.sanitize(self.release, self.now)
        self.assertEqual(set(feed), {"schema", "repository", "syncedAt", "release"})
        self.assertNotIn("author", feed["release"])
        self.assertNotIn("body", feed["release"])
        self.assertEqual(feed["release"]["assets"][0]["digest"], "sha256:" + "a"*64)

    def test_bad_or_unfinished_release_is_not_published(self):
        for path, value in [("draft", True), ("prerelease", True), ("tag_name", "../wrong")]:
            invalid = copy.deepcopy(self.release)
            invalid[path] = value
            with self.assertRaises(AssertionError):
                updates.sanitize(invalid, self.now)
        for field, value in [("digest", ""), ("state", "new"), ("browser_download_url", "https://evil.test/package")]:
            invalid = copy.deepcopy(self.release)
            invalid["assets"][0][field] = value
            with self.assertRaises(AssertionError):
                updates.sanitize(invalid, self.now)

    def test_fixed_number_of_requests_and_only_metadata_branch_mutated(self):
        calls = []
        def api(path, method="GET", data=None):
            calls.append((path, method, data))
            if path.endswith("releases/latest"): return self.release
            if path.endswith("git/ref/heads/updates"): return {"object": {"sha": "previous"}}
            if path.endswith("git/trees"):
                self.assertEqual([node["path"] for node in data["tree"]], ["zero-release.json"])
                return {"sha": "tree"}
            if path.endswith("git/commits"):
                self.assertEqual(data["parents"], ["previous"])
                return {"sha": "next"}
            self.assertEqual(path, f"/repos/{updates.REPO}/git/refs/heads/updates")
            self.assertFalse(data["force"])
            return {}
        updates.publish(api, self.now)
        self.assertEqual(len(calls), 5)
        self.assertEqual(sum(path.endswith("releases/latest") for path, _, _ in calls), 1)


if __name__ == "__main__":
    unittest.main()

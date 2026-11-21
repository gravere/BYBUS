#!/usr/bin/env python3
"""Publish a small validated upstream release feed. Credentials never enter the public JSON."""
import datetime as dt
import json
import os
import re
import urllib.error
import urllib.request

REPO = "gravere/BYBUS"
UPSTREAM = "LocalizeLimbusCompany/LocalizeLimbusCompany"


def sanitize(release, now):
    tag = release["tag_name"]
    assert re.fullmatch(r"[0-9]{1,18}", tag) and int(tag) > 0
    assert release["draft"] is False and release["prerelease"] is False
    page = f"https://github.com/{UPSTREAM}/releases"
    assert release["html_url"] == f"{page}/tag/{tag}"
    published = dt.datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))
    assert dt.datetime(2000, 1, 1, tzinfo=dt.timezone.utc) < published <= now + dt.timedelta(minutes=5)
    name = f"LimbusLocalize_{tag}.7z"
    matches = [asset for asset in release["assets"] if asset.get("name") == name]
    assert len(matches) == 1
    asset = matches[0]
    assert asset["state"] == "uploaded"
    assert asset["browser_download_url"] == f"{page}/download/{tag}/{name}"
    assert isinstance(asset["size"], int) and 0 < asset["size"] <= 1024**3
    assert re.fullmatch(r"sha256:[0-9a-fA-F]{64}", asset.get("digest") or "")
    return dict(schema=1, repository=UPSTREAM, syncedAt=now.isoformat().replace("+00:00", "Z"),
        release=dict(tag_name=tag, published_at=release["published_at"], html_url=release["html_url"],
                     draft=False, prerelease=False, assets=[{key: asset[key] for key in
                         ("name", "size", "browser_download_url", "digest")}]))


def publish(api, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    feed = sanitize(api(f"/repos/{UPSTREAM}/releases/latest"), now)
    base = f"/repos/{REPO}"
    try:
        parent = api(f"{base}/git/ref/heads/updates")["object"]["sha"]
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        parent = None
    # An orphan metadata branch contains only public facts, not source files or credentials.
    tree = api(f"{base}/git/trees", "POST", {"tree": [dict(path="zero-release.json", mode="100644",
        type="blob", content=json.dumps(feed, ensure_ascii=False, indent=2) + "\n")]})
    commit = api(f"{base}/git/commits", "POST", dict(message="Refresh public translation version",
        tree=tree["sha"], parents=[parent] if parent else []))
    if parent:
        api(f"{base}/git/refs/heads/updates", "PATCH", dict(sha=commit["sha"], force=False))
    else:
        api(f"{base}/git/refs", "POST", dict(ref="refs/heads/updates", sha=commit["sha"]))
    print("Published upstream version " + feed["release"]["tag_name"])
    return feed


def main():
    assert os.environ.get("GITHUB_REPOSITORY") == REPO
    token = os.environ["GITHUB_TOKEN"]

    def api(path, method="GET", data=None):
        assert path.startswith(f"/repos/{REPO}/git/") or path == f"/repos/{UPSTREAM}/releases/latest"
        request = urllib.request.Request("https://api.github.com" + path, method=method,
            data=None if data is None else json.dumps(data).encode(), headers={
                "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "BYBUS-PublicUpdates"})
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read(2 * 1024 * 1024 + 1)
            assert len(payload) <= 2 * 1024 * 1024
            return json.loads(payload)

    publish(api)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Validate stable release tags and keep tag-derived versions out of tracked files."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ("directstream", "directstream-api", "directstream-runtime", "directstream-ui", "directstream-firebase")
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def parse_version(value):
    if not VERSION.fullmatch(value):
        raise ValueError("Use stable X.Y.Z versions without prefixes, leading zeros or prerelease suffixes.")
    parts = tuple(map(int, value.split(".")))
    if parts[0] > 2099 or parts[1] > 999 or parts[2] > 999:
        raise ValueError("Android versionCode encoding requires major <= 2099 and minor/patch <= 999.")
    code = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    if code <= 0:
        raise ValueError("Android versionCode must be positive; v0.0.0 is not a release.")
    return parts, code


def properties():
    return dict(re.findall(r"^([A-Za-z][A-Za-z0-9]*)=(.+)$", (ROOT / "gradle.properties").read_text(), re.M))


def git(*args):
    return subprocess.check_output(["git", *args], text=True, cwd=ROOT).strip()


def validate_tag(tag):
    if not tag.startswith("v"):
        raise ValueError("Release tags must have the form vX.Y.Z.")
    version = tag[1:]
    parts, code = parse_version(version)
    head = git("rev-parse", "HEAD")
    if git("rev-parse", f"refs/tags/{tag}^{{commit}}") != head:
        raise ValueError("The checked-out commit must match the pushed tag.")
    if subprocess.run(["git", "merge-base", "--is-ancestor", head, "refs/remotes/origin/main"], cwd=ROOT).returncode:
        raise ValueError("Release tags must point to a commit reachable from origin/main.")
    for other in git("tag", "--merged", "origin/main", "--list", "v*").splitlines():
        if other == tag:
            continue
        try:
            previous, _ = parse_version(other[1:])
        except ValueError:
            continue
        if previous >= parts:
            raise ValueError(f"Release version must be newer than existing main tag {other}.")
    return version, code


def central_url(group, artifact, suffix):
    return f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{suffix}"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "DirectStream-release-ci"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError(f"Central returned HTTP {error.code}; refusing to assume the version is available.") from None


def require_unpublished(group, version):
    candidate, _ = parse_version(version)
    for artifact in ARTIFACTS:
        if fetch(central_url(group, artifact, f"{version}/{artifact}-{version}.pom")) is not None:
            raise ValueError(f"{artifact}:{version} is already public. Central versions are immutable; create a newer tag.")
        metadata = fetch(central_url(group, artifact, "maven-metadata.xml"))
        if metadata is not None:
            for node in ET.fromstring(metadata).findall("./versioning/versions/version"):
                try:
                    released, _ = parse_version(node.text or "")
                except ValueError:
                    continue
                if released >= candidate:
                    raise ValueError(f"{artifact} already has release {node.text}; choose a newer version.")


def wait_for_publication(group, version, timeout):
    deadline = time.monotonic() + timeout
    pending = set(ARTIFACTS)
    while pending:
        for artifact in tuple(pending):
            if fetch(central_url(group, artifact, f"{version}/{artifact}-{version}.pom")) is not None:
                pending.remove(artifact)
        if not pending:
            print(f"All five {version} artifacts are available from Maven Central.")
            return
        if time.monotonic() >= deadline:
            raise TimeoutError("Central propagation timed out. Inspect the existing Portal deployment before rerunning; do not re-upload blindly.")
        time.sleep(min(30, max(0, deadline - time.monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("version", "check", "wait"))
    parser.add_argument("--tag")
    parser.add_argument("--version")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--github-env", type=Path)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    defaults = properties()
    if args.command == "check":
        version, code = validate_tag(args.tag or os.environ.get("GITHUB_REF_NAME", ""))
        require_unpublished(defaults["sdkGroup"], version)
    else:
        version = args.version or defaults["sdkVersion"]
        _, code = parse_version(version)
    if args.command == "wait":
        wait_for_publication(defaults["sdkGroup"], version, args.timeout)
        return
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"version={version}\nversion-code={code}\n")
    if args.github_env:
        with args.github_env.open("a") as output:
            output.write(f"SDK_VERSION={version}\nSHOWCASE_VERSION_CODE={code}\n")
    print(f"SDK version: {version}; showcase versionName: {version}; versionCode: {code}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        print(f"Release check failed: {error}", file=sys.stderr)
        raise SystemExit(1)

# CI and tag releases

The SDK release workflow publishes all five modules automatically when you push a
stable `vX.Y.Z` tag whose commit is reachable from `main`. For example, `v1.0.1`
produces Maven version `1.0.1`, the runtime constant `1.0.1`, and showcase
`versionName = "1.0.1"`. No `v` is included in Maven coordinates.

## Version names and Android version codes

Maven libraries have a version string and do not need Android `versionCode`.
The showcase APK needs both. Its code is derived deterministically:

```text
versionCode = major * 1,000,000 + minor * 1,000 + patch
v1.0.0 -> 1000000
v1.0.1 -> 1000001
v1.1.0 -> 1001000
v2.0.0 -> 2000000
```

This increases for each newer stable version and stays the same on a job retry.
Minor and patch components must be 0–999, major 0–2099; `0.0.0`, leading zeros,
prerelease/build suffixes and malformed tags are rejected. These limits keep the
code positive and below Android's maximum. If an already-distributed APK used a
larger code, review the numbering scheme before releasing an update to it.

`gradle.properties` remains the developer default. CI passes `-PsdkVersion=X.Y.Z`
through every SDK, showcase and standalone-consumer build. It never edits tracked
files, commits a counter or moves a tag. Update the developer default and public
README examples when preparing the next source release; the tag is authoritative
for release artifacts even if that default has not yet been bumped.

## Configure GitHub secrets once

In [repository Settings → Environments](https://github.com/Orthoepiccrown0/webrtc-android/settings/environments),
create an environment named **`maven-central`**. Add these environment secrets:

| Secret                   | Value                                                                                |
| ------------------------ | ------------------------------------------------------------------------------------ |
| `MAVEN_CENTRAL_USERNAME` | Username from your Central Portal user token.                                        |
| `MAVEN_CENTRAL_PASSWORD` | Password from that same token, not your account login password.                      |
| `SIGNING_KEY`            | Entire ASCII-armored private GPG key, including BEGIN/END lines and actual newlines. |
| `SIGNING_PASSWORD`       | Private key passphrase. Leave unset if the key is unprotected.                       |

Use the same signing identity whose public key is on Ubuntu's keyserver. The
public key, its ID, Firebase client configuration and APK signing keystore do not
replace the private GPG key. No `SIGNING_KEY_ID` secret is needed: the exported key
selects the signing identity. GitHub's multiline secret uses actual newlines;
Java-properties-style literal `\n` escapes are not needed here.

For an automatic release, leave required environment reviewers disabled. You can
choose required reviewers if you want approval before the publish job instead.
Restrict environment deployment branches/tags to tags matching `v*`, and use a
repository tag ruleset to restrict creation/update/deletion of release tags to
release maintainers. The workflow separately enforces the exact tag syntax and
`main` ancestry. Never supply release secrets to pull-request jobs.

If you already use the [GitHub CLI](https://cli.github.com/manual/gh_secret_set),
after creating the environment you can configure secrets without putting values
in command arguments or repository files:

```sh
gpg --armor --export-secret-keys C89C78F7EA64BA09 | \
  gh secret set SIGNING_KEY --env maven-central --repo Orthoepiccrown0/webrtc-android
gh secret set SIGNING_PASSWORD --env maven-central --repo Orthoepiccrown0/webrtc-android
gh secret set MAVEN_CENTRAL_USERNAME --env maven-central --repo Orthoepiccrown0/webrtc-android
gh secret set MAVEN_CENTRAL_PASSWORD --env maven-central --repo Orthoepiccrown0/webrtc-android
```

The last three commands prompt for their secret values. Omit `SIGNING_PASSWORD`
for an unprotected key. The key-export command pipes the private block directly
to GitHub CLI; do not run it without the pipe or paste its output into chat/logs.
Retain an encrypted offline backup and the public-key revocation certificate.

No showcase Firebase/Worker configuration or application signing key is required.
The CI showcase uses empty host configuration; tests use the isolated fake-provider
runner. CI does not deploy Firebase, the Worker, D1 or any live application.

## Release a new version

First merge the workflow and the reviewed SDK changes to `main`, and configure
the environment secrets. From an up-to-date checkout of `main`:

```sh
git switch main
git pull --ff-only
git tag -a v1.0.1 -m "DirectStream 1.0.1"
git push origin v1.0.1
```

`1.0.0` is already published, so use a newer version. Creating a tag only locally
does not start GitHub Actions; pushing it does. The tag must contain the workflow
files. Both annotated and lightweight tags work, but annotated tags are recommended.
A GitHub Release is optional and is not the workflow trigger.

The release workflow:

1. Validates tag syntax, checkout/tag identity and `origin/main` ancestry.
2. Rejects existing coordinates or versions older than published metadata/main
   tags, including after a Git history rewrite.
3. Runs API/boundary/formatting checks, JVM tests, showcase lint and debug/optimized
   release builds, both local Maven-only consumer flavors, metadata/JNI/R8 checks,
   and the isolated SDK emulator suite on API 35.
4. Starts the `maven-central` environment job, builds/signs all five modules, checks
   publication contents/version/signature files, and opts into **automatic Central
   release** with `-PsdkAutomaticRelease=true`.
5. Waits for all five public Maven Central coordinates and writes the version,
   installation coordinate and showcase code into the job summary.

Ordinary pushes to `main`/`codex/**` and pull requests run the verification workflow
without secrets or publication. Actions are pinned to immutable commit hashes.
Signing-job caching and Gradle configuration caching are disabled; only test reports
are uploaded. Release workflows are serialized and do not cancel an active release.

If publication fails or times out, inspect the existing Central Portal deployment
before retrying. A release may already be propagating. Never delete/move a released
tag or try to overwrite a Central version. If any module is public, a new tag/version
is required for a new upload. A partial Central failure needs manual inspection.

Local validation used a `1.0.1` override while the tracked default remained `1.0.0`:
75 JVM tests, nine release-policy tests (including real Git histories), debug and
optimized release showcase/consumer builds, lint, API/boundary checks, metadata,
native/JNI/R8 checks and all 25 candidate signatures passed. Both showcase APKs
reported name `1.0.1` and code `1000001`. No candidate tag or Central upload was created.

The workflow has been prepared and validated locally; the first hosted run still
needs to be observed after these files reach GitHub. Emulator tests do not replace
physical-device audio, background/FCM cold-start or restrictive-network checks in
[VALIDATION.md](VALIDATION.md). Local manual publishing remains available through
[PUBLICATION.md](PUBLICATION.md), with automatic public release disabled by default.

References: [GitHub tag triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow),
[GitHub environment secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets),
[publishing plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

# Rolling GitHub pipeline release

Successful **protected `develop` push pipelines** from
`git.tak.gov/core/weartak-core/wearos-tak-civ` publish the final production-signed
`develop_release` APK to the fixed GitHub mirror
`Ascent-Integrated-Tech/wearos-tak-civ`. The visible title is exactly
**TAK.gov CI/CD Pipeline Release**.

By project policy, `develop` builds are stable: this release uses
`prerelease=false`, not a prerelease. This classification is an explicit project
decision, not an implication of using Android's release signing/build variant.
The actual APK versionName/versionCode, canonical source SHA, pipeline ID/IID,
pipeline URL, and APK SHA256 appear in the release body. The rolling release
does not take over GitHub's "Latest" designation from other numbered releases.
Official `v...` pipelines and all existing numbered/stable releases remain unchanged.

## Administrator setup (separate approval required)

1. Public signed-APK distribution to the exact fixed destination
   `Ascent-Integrated-Tech/wearos-tak-civ` was explicitly approved on 2026-10-19.
   The mirror was private at the original setup and is now public. The publisher
   permits either visibility for this approved destination only and checks its
   exact identity and active status before every update. Other, archived, disabled
   or unknown-status destinations fail closed. This job never changes repository
   visibility or access; any different destination needs separate approval.
2. Protect canonical GitLab `develop` and restrict who may push/merge, edit pipeline
   configuration, use protected runners, or override pipeline variables.
3. In the GitHub mirror, create the fixed tag `wearos-development` on an existing
   mirrored commit, with separate approval. There is currently no such tag.
   Do not move the tag on each build. The publisher checks that it exists before
   creating a release, never pushes commits, and has no tag-management calls.
   The mirror was behind canonical `gov/develop` at inspection, so a GitLab
   source SHA must **not** be used as an unverified GitHub `target_commitish`.
   The fixed tag is only an anchor; the APK's authoritative source SHA is in the
   release metadata. GitHub's automatic source archives refer to the fixed anchor,
   not necessarily the APK source. The job uploads **only the signed APK**, never
   custom source bundles, logs, checksums as assets, signing files, or credentials.
4. Create a fine-grained GitHub token scoped **only** to this mirror with
   **Contents: read and write** (metadata read is implicit), an appropriate expiry,
   and organization approval/SSO authorization if required. No administration,
   workflows, issues, or other repository permissions are needed. Restrict the
   token owner to this automation as appropriate. Do not copy local `gh`/`glab`
   credentials or reuse a broad personal token.
5. Add `WEARTAK_GITHUB_TOKEN` to the canonical GitLab project's CI/CD variables as
   **masked**, **protected**, and hidden if supported; disable variable expansion.
   Make it available to the publish job's environment scope. Never place it in YAML,
   repository files, logs, or artifacts. Rotate it according to organization policy.
6. Allow the existing runner to pull `python:3.12-slim` (stdlib only, no package
   installation), and allow HTTPS to `api.github.com`, `uploads.github.com`, and
   GitHub's signed release-asset storage endpoints. The Android build continues
   using the existing TAK Gradle launcher and production-certificate verification.
7. Review/approve and merge these changes through the normal ticket process.
   A subsequent authorized protected `develop` push will run publication. Do not
   trigger a pipeline or publish manually merely to complete setup.

Missing variables, missing anchor tag, invalid artifacts, denied API access, and
verification failures fail the publish job with actionable diagnostics. The build's
signed APK remains available as a GitLab artifact for 30 days even when publication
fails. An unprotected pipeline is intentionally excluded from publication by rules,
not reported as a successful upload. The script also enforces the source gates.

## Ordering and safe retention

The final `publish` stage waits for **all** earlier jobs to succeed; it downloads
artifacts only from this pipeline's `develop_release`. That job verifies the
production signing certificate, then writes `verified-apk.sha256`. The publisher
requires exactly one APK matching this checksum and Gradle release metadata.
The checksum and metadata are GitLab-only provenance, not additional GitHub assets.

GitLab's `wearos-github-development` resource group serializes all updates from
this project regardless of pipeline ordering. Keep this as the **only writer**
to the rolling release; do not run the script elsewhere or edit its ownership
marker manually. The release's project ID and pipeline IID prevent an older
pipeline (including retries) from replacing a successfully published newer one.
Reusing a pipeline IID with different bytes/source fails rather than overwriting.
This is not a cross-project/distributed lock against other writers.

The first release is created as a draft. Each APK receives a unique pipeline/hash
name, is uploaded alongside the previous APK, then is downloaded using the
authenticated asset API and checked for exact size and SHA256. Only after that
does the job publish/update the release metadata and confirm it by reading it back.
It then deletes **only older assets on this one release** with both the exact
`weartak-development-p<IID>-<SHA256>.apk` naming scheme and its matching
`weartak-rolling-v1:<SHA256>` ownership label.

Other releases, tags, unrelated assets, and future-pipeline assets are untouched.
There is no historical-release sweep. A failure before replacement verification
leaves previous usable assets in place; a failed first upload may leave a draft.
Failure during cleanup is reported as a failure even if the new APK is usable.
Retrying the same job reuses and re-verifies a matching existing upload and resumes
cleanup; it does not blindly overwrite assets. GitHub updates are not transactional:
a lost response may leave a new asset or updated metadata, but never justifies
deleting old usable assets without verification.

If an upload is incomplete/corrupt, the retry fails safely rather than deleting it
blindly. An authorized administrator must inspect/remove **only that pipeline's
failed asset**, then retry. Do not remove the previous usable APK, other assets,
the tag, or release ownership metadata. A human-created release on the reserved tag
without the expected ownership marker is refused, not silently adopted.

## Local validation

Run from the repository root with Python 3.12:

```text
python -m unittest discover -s ci/tests -v
```

Tests use in-memory mocked GitHub responses/fixtures, never a token or real uploads.
They cover source/configuration gates, artifact integrity, initial stable publication,
ordering, repeat retries, ownership and retention boundaries, API/verification failures,
pagination, and stripping authorization on storage redirects. These checks do not
prove runner registry/network access, protected-variable delivery, token authorization,
or real GitHub upload behavior. Those remain end-to-end acceptance checks after
separate approval and administrator setup. No Android build is needed for CI-only tests.

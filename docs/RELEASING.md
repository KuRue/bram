# APK releases

Bram has two GitHub Actions paths:

| Workflow | Trigger | Result |
| --- | --- | --- |
| Android CI | Pull requests, pushes to `main`, manual runs | Unit tests, lint, debug APK artifact (14 days) |
| Android release | Push a `v*` tag, or manually retry an existing tag | The same CI checks, release lint, signed ARM64 APK, SHA-256 checksum, draft GitHub release |

The release workflow never publishes a release. Review the draft and explicitly publish it
when ready. No model files are bundled. Standard hosted builds include CPU and Vulkan, but
not the optional Hexagon SDK or OpenCL backend. Hardware validation is separate from CI.

## One-time signing setup

Use one permanent **app signing key** for all sideloaded releases. Android accepts updates only
when the package and signing identity match. Keep an offline backup of the keystore and its
passwords; losing the key prevents updates to existing installations. See
[Android app signing](https://developer.android.com/studio/publish/app-signing) for the signing lifecycle.

If Bram already has a release key, use it. Otherwise generate one locally (the command prompts
for passwords; do not put them in shell history):

```bash
keytool -genkeypair -v -keystore bram-release.jks -alias bram \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keystores are git-ignored. Never commit the key or passwords. Set these repository Actions secrets
in **Settings → Secrets and variables → Actions**, or through `gh`:

| Secret | Value |
| --- | --- |
| `BRAM_RELEASE_KEYSTORE_BASE64` | Base64-encoded permanent keystore |
| `BRAM_RELEASE_STORE_PASSWORD` | Keystore password |
| `BRAM_RELEASE_KEY_ALIAS` | Signing key alias, e.g. `bram` |
| `BRAM_RELEASE_KEY_PASSWORD` | Key password |

From a trusted Linux machine with `gh` authenticated to the repository:

```bash
base64 -w 0 bram-release.jks | gh secret set BRAM_RELEASE_KEYSTORE_BASE64 --repo KuRue/bram
gh secret set BRAM_RELEASE_STORE_PASSWORD --repo KuRue/bram
gh secret set BRAM_RELEASE_KEY_ALIAS --repo KuRue/bram
gh secret set BRAM_RELEASE_KEY_PASSWORD --repo KuRue/bram
```

The last three commands prompt for values. The workflow decodes the key into runner temporary
storage, signs through Gradle, verifies the APK with `apksigner`, and removes the temporary key
in an always-run cleanup step. Missing secrets fail the release build; there is no debug-key fallback.
PR verification has no access to these signing secrets.

## Prepare a version

1. Update `versionName` and increase `versionCode` in `app/build.gradle.kts`. The name must match
   the tag without its `v` prefix; the code must be greater than every previous published code.
2. Merge the change after verification. Complete the focused device and cold-AVD gates before
   deciding to distribute it, plus any hardware-specific checks needed for the release.
3. When authorized to prepare the release, tag that exact commit and push the tag:

   ```bash
   git tag -a v0.2.0-alpha01 -m 'Bram 0.2.0-alpha01'
   git push origin v0.2.0-alpha01
   ```

   This is an example, not a tag to reuse for later versions.
4. Watch **Actions → Android release**. The pipeline verifies the source, builds the release APK,
   checks that its embedded version matches the tag, and creates a draft with generated notes,
   `bram-v…-arm64.apk`, and `SHA256SUMS`.
5. Download and smoke-test the signed APK on a compatible phone, including upgrading from the
   previous signed release. Review the notes, mark alpha/beta versions as prereleases, and
   publish the draft when approved.

If a run fails after the tag is pushed, fix credentials or infrastructure and retry with
**Run workflow**, selecting **the tag itself** as the workflow ref and entering the same tag in
the `tag` input. The verified source and packaged source must be identical. Source fixes need a
new version tag; do not move released tags. Retries can replace draft assets, but refuse to
replace assets on a published release.

## Local release build

Set `BRAM_RELEASE_KEYSTORE` to the absolute path of the permanent keystore, and supply
`BRAM_RELEASE_STORE_PASSWORD`, `BRAM_RELEASE_KEY_ALIAS`, and `BRAM_RELEASE_KEY_PASSWORD` through
your local secret manager/environment. Then run:

```bash
./gradlew :app:lintRelease :app:assembleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`. Without a release keystore,
local `assembleRelease` produces an unsigned APK for inspection; it is not installable until signed.
Never distribute an APK signed with a temporary test key.

Release APKs and existing debug installs have different signatures. Changing between them
requires removing the previous installation, which clears its app data. Plan backups first.

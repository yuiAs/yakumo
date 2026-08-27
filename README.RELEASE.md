# Release builds

How to produce a signed release APK of やくも (Yakumo) — locally, or through the
GitHub Actions workflow. For the general toolchain setup (JDK, Android SDK/NDK,
Rust, cargo-ndk) see [README.md](README.md#building); a release build needs the
same prerequisites as a debug build, because the Rust core and the sherpa-onnx
prebuilts are compiled/fetched during the Gradle build either way.

> **Signing is optional to the build, not to shipping.** With no credentials
> present, `assembleRelease` still succeeds and emits an **unsigned** APK
> (`app-release-unsigned.apk`), which Android will refuse to install. Configure
> signing before you distribute anything.

---

## 1. Create a keystore (once)

A keystore holds the key that identifies you as the publisher. Android only
accepts an update if it is signed with the *same* key as the installed app, so
this file is created once and then kept for the lifetime of the app.

```sh
keytool -genkeypair -v \
  -keystore yakumo-release.jks \
  -alias yakumo \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

`keytool` ships with the JDK. It prompts for a keystore password, a key password
(pressing Enter reuses the keystore password), and a distinguished name — only
"first and last name" (CN) really matters, and any stable identifier is fine for
a personally distributed app.

- `-validity 10000` is ~27 years. Anything shorter risks the key expiring before
  the app does.
- Put the file wherever you like; `android/yakumo-release.jks` is convenient
  because `android/.gitignore` already excludes `*.jks` and `*.keystore`.

> ⚠️ **Back up the `.jks` and both passwords, off this machine.** Losing them
> means you can never publish an update that existing installs will accept —
> users would have to uninstall and reinstall, losing their local data. There is
> no recovery path.

---

## 2. Give the build the credentials

`android/app/build.gradle.kts` reads the four signing values from
`android/keystore.properties` first, then falls back to environment variables.
Use whichever fits; the property file is the practical choice locally, the
environment variables are what CI uses.

### Option A — `keystore.properties` (local)

A tracked template is already in the repo:

```sh
cp android/keystore.properties.template android/keystore.properties
```

Then fill it in:

```properties
# Path to the keystore, relative to the android/ project root (or absolute).
storeFile=yakumo-release.jks
storePassword=<keystore password>
keyAlias=yakumo
keyPassword=<key password>
```

`android/keystore.properties` is gitignored, so the secrets stay local.

### Option B — environment variables

| Variable | Maps to | Example |
|---|---|---|
| `YAKUMO_KEYSTORE_FILE` | `storeFile` | `/tmp/release.keystore` |
| `YAKUMO_KEYSTORE_PASSWORD` | `storePassword` | |
| `YAKUMO_KEY_ALIAS` | `keyAlias` | `yakumo` |
| `YAKUMO_KEY_PASSWORD` | `keyPassword` | |

Both paths resolve `storeFile` through `rootProject.file(...)`, i.e. relative to
`android/`. Absolute paths work too, which is how CI points at the keystore it
decodes into a temp directory.

A blank or missing value counts as "not set": if any of the four is absent, the
release signing config is not declared at all and the build falls through to
unsigned rather than failing.

---

## 3. Bump the version

Before tagging a release, update both fields in `android/app/build.gradle.kts`:

```kotlin
versionCode = 3        // integer, must strictly increase for every shipped build
versionName = "0.3.0"  // human-readable, shown in Settings → About
```

`versionCode` is what Android compares when deciding whether an APK is an
update — a build with an equal or lower code cannot be installed over the
current one.

The About line also carries the short git hash (`BuildConfig.GIT_HASH`, filled in
by a `git rev-parse` at configuration time), so a release build made from a dirty
or unexpected commit is identifiable after the fact.

---

## 4. Build

```sh
cd android
./gradlew :app:assembleRelease --console=plain
```

On Windows use `.\gradlew.bat`. The build runs `fetchSherpaPrebuilt` and
`cargoBuildRustCore` first (see [README.md](README.md#build--install)), so the
first release build after a clean checkout downloads the prebuilt native libs and
cross-compiles the Rust core.

Output:

| Signing configured | Path |
|---|---|
| yes | `android/app/build/outputs/apk/release/app-release.apk` |
| no | `android/app/build/outputs/apk/release/app-release-unsigned.apk` |

The release build type has `isMinifyEnabled = false`, so no R8 shrinking or
obfuscation is applied — worth knowing if you expect a much smaller APK than the
debug one. Size is dominated by the bundled native libs either way; the ONNX
models are downloaded at first run and are not in the APK.

---

## 5. Verify the signature

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs --verbose \
  android/app/build/outputs/apk/release/app-release.apk
```

Expect `Verified using v2 scheme` (and v3, on recent build-tools) plus the
certificate's DN and SHA-256 digest. Record that digest somewhere — it is how you
confirm later that a build came from the same key.

Install it on a connected device:

```sh
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Release and debug builds can coexist: the debug variant carries a `.debug`
`applicationId` suffix and a `Yakumo (debug)` / `やくも (debug)` launcher label.

---

## 6. Releasing through CI

`.github/workflows/release.yml` builds and signs the same APK on a GitHub runner.

### Repository secrets

Set these under **Settings → Secrets and variables → Actions**:

| Secret | Contents |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | The `.jks` file, base64-encoded on a single line |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (`yakumo`) |
| `RELEASE_KEY_PASSWORD` | Key password |

Encode the keystore:

```sh
# Linux / macOS / Git Bash
base64 -w0 android/yakumo-release.jks > keystore.b64
```

```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("android\yakumo-release.jks")) |
  Set-Content -NoNewline keystore.b64
```

Paste the contents of `keystore.b64` as the secret value, then delete the file.
The workflow decodes it back into `$RUNNER_TEMP/release.keystore` and passes the
path through `YAKUMO_KEYSTORE_FILE`.

### Triggering a build

- **Tag push** — the release path:

  ```sh
  git tag v0.3.0
  git push origin v0.3.0
  ```

  The workflow builds the APK, renames it to `yakumo-<tag>.apk`, and attaches it
  to the GitHub Release for that tag (creating the Release if needed).

- **Manual run** — *Actions → Release build → Run workflow*. Same build, but the
  APK is only uploaded as a workflow artifact (`yakumo-release-apk`), which is
  useful as a dry run before tagging.

Make the version bump (step 3) part of the commit you tag, so the tag, the
`versionName`, and the About line all agree.

The workflow checks out with `lfs: true`: `gradle-wrapper.jar` is stored in Git
LFS (see `.gitattributes`), and the build fails on a pointer file without it.

---

## Troubleshooting

**The APK is named `app-release-unsigned.apk`.**
None of the four credentials reached the build. Check that
`android/keystore.properties` exists (not just the `.template`) and that no value
is left blank — a blank is treated the same as unset.

**`Keystore was tampered with, or password was incorrect`.**
Wrong `storePassword`, or `storeFile` points at something that is not the
keystore. Remember the path is resolved relative to `android/`.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing.**
The installed app was signed with a different key (for example an earlier
unsigned or debug-signed build). Uninstall it first —
`adb uninstall app.rly3h.yakumo` — which also clears its downloaded models and
saved sessions.

**`INSTALL_FAILED_VERSION_DOWNGRADE`.**
Android refuses to install over an app with an equal or higher `versionCode`.
Bump it, or uninstall first.

**`cargo: command not found` / NDK errors, locally or in CI.**
The release build compiles the Rust core like any other build. Re-check the
[prerequisites](README.md#prerequisites): Rust with the `aarch64-linux-android`
and `x86_64-linux-android` targets, `cargo-ndk`, and NDK 27.2.12479018 reachable
via `ANDROID_NDK_HOME`.

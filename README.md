# APK Repacker

A native Android app for **legitimate** APK inspection, backup, package-rename, and
re-signing of apps you own or are permitted to modify. All processing happens **on the
device** — no PC, no Termux, no shell, no server, and signing keys never leave the phone.

> Renaming an app's package identity can break it (certificate pinning, Play services,
> OAuth/Firebase, DRM, reflection, native assumptions, splits). The app shows this
> warning before every run and never claims arbitrary APKs can always be renamed.

## What it does

1. Lists installed apps (PackageManager) and lets you pick one.
2. Copies the selected APK out of its install location into app-private storage
   (the original is only ever read; split APKs are detected and called out).
3. Analyzes it: package, version, min/target SDK, DEX count, native ABIs, split status,
   and potential issues.
4. Rewrites the **package identity** across the files that actually carry it:
   the binary manifest and the DEX type references — never by blind byte replacement.
5. Rebuilds + zipaligns, signs (APK Signature Scheme v1+v2+v3), verifies, and writes the
   output where you can install it via the system package installer (FileProvider).

## Toolchain (all runs on-device)

| Concern | Implementation |
|---|---|
| Install discovery / extraction | Android `PackageManager` + `ApplicationInfo.sourceDir` |
| Binary manifest (AXML) rewrite | In-tree `com.apkrepacker.apk.axml` — documented chunk format, string-pool-preserving rewrite |
| DEX namespace rewrite | **smali/dexlib2** (`com.android.tools.smali:smali-dexlib2`) — type-descriptor aware, conservative |
| Rebuild + zipalign | In-tree `ApkBuilder` (STORED entries aligned 4B / 4096B for `.so`) |
| Signing + verification | **Google apksig** (`com.android.tools.build:apksig`) |
| Signing key | **AndroidKeyStore** (hardware-backed, non-exportable) by default; optional user keystore import |

No desktop-only tools (aapt/apktool binaries) are required: the manifest and DEX are
rewritten surgically, and all other entries (resources.arsc, res/, assets/, lib/) are
copied through verbatim.

## Package-rename strategy (why it's conservative)

- **Manifest:** the `package` attribute is set to the new ID. Pooled strings equal to the
  old package, or prefixed `oldpkg.`, are rewritten (component names, authorities,
  permissions). Relative names like `.MainActivity` are left alone — they resolve under
  the new package automatically.
- **DEX:** only **type descriptors** (`Lcom/old/pkg/...;`) are remapped. String constants
  are untouched, because an app may legitimately hold the old name as a URL, DB name,
  reflection target, or SDK config.
- **Resources / native libs:** preserved. The resource table package and ELF binaries are
  not rewritten by the normal rename (that would need a dedicated, deliberate feature).

## Architecture

```
app/ui/        MainActivity, AppListActivity, ResultActivity, Installer
app/apk/       RepackageEngine + stages: ApkExtractor, ApkAnalyzer, ApkDecoder,
               PackageTransformer, DexTransformer, ApkBuilder, ApkSigner, ApkVerifier
app/apk/axml/  AxmlFile (binary-manifest editor)
app/security/  KeystoreManager
app/storage/   ApkStorage, SafeZip
```

All APK work runs off the UI thread; a live, scrollable build log and a stage checklist
(`✓ / … / ✗`) report progress. Failures are stage-specific with a concrete reason and
always note that the original APK was not modified.

## Security

Processes locally; never uploads APKs or keys; never executes extracted code; treats APK
contents as untrusted. ZIP extraction rejects `../`/absolute paths (zip-slip) and enforces
per-entry and total size ceilings (decompression bombs). Decode scratch lives under
`filesDir` (not the OS-evictable cache) so live inputs survive the whole run. Temp files
are cleaned up unless you tick "keep intermediates". The original installed APK is never
overwritten.

## Build

Requires **JDK 21** (Gradle 8.9 / AGP 8.7 reject JDK 25). A JBR 21 is already available
locally; `gradle.jdk.env` points `JAVA_HOME` at it:

```bash
source gradle.jdk.env
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # run the pipeline tests
```

## Tests

`app/src/test/.../RepackagePipelineTest` exercises the real pipeline classes on the JVM
against a real APK fixture (everything except the Android-only glue):

- package-name validation rules,
- AXML package read → rename → re-parse round trip,
- full **decode → manifest rewrite → DEX remap → rebuild → sign (v1/v2/v3) → verify**,
  asserting the rebuilt manifest reports the new package, `resources.arsc` is preserved,
  and apksig reports the signature **VALID**.

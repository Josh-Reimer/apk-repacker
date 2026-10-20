# APK Repacker

An Android app that copies an installed app's APK, gives it a **new package name**, re-signs
it with your own key, and verifies the result, so the renamed copy can be installed next to
the original. Everything runs **on the phone**: no PC, no Termux, no server, and signing
keys never leave the device.

It is meant for inspecting, backing up, and modifying apps **you own or have permission to
modify**.

> **Renaming an app can break it.** Apps may check their own signing certificate, rely on
> Google Play services, OAuth or Firebase registrations tied to the original package and
> certificate, use DRM, reflection, or native code that assumes the original name, or ship
> as split APKs. APK Repacker shows this warning before every run and never claims that an
> arbitrary APK can be renamed successfully. The renamed copy can never be signed with the
> original developer's key, so it cannot update the original app.

## What it does

1. Lists installed apps (optionally including system apps) and lets you pick one.
2. Copies the APK out of its install location into app-private storage. The installed
   original is only ever read. Split APKs are detected and called out, and native libraries
   that preinstalled apps keep *outside* their APK are copied too.
3. Analyzes the copy: package, version, min/target SDK, DEX count, native ABIs, split
   status, and likely problems.
4. Rewrites the package identity in the places that actually carry it (see below), never by
   blind byte replacement.
5. Optionally sets a new **display name** and **watermarks the launcher icon** so the copy
   is easy to tell apart from the original.
6. Rebuilds and zip-aligns the APK, signs it (APK Signature Scheme v1/v2/v3 as the min SDK
   requires), verifies the signature, and checks that no provider authority will collide
   with an installed app.
7. Shows a stage-by-stage progress bar and build log, then offers **Install APK**, which
   opens Android's normal package installer. Nothing is installed without your approval.

The result screen shows which APK was used, the requested package name, the signing
certificate, and whether the output passed structural and signature verification.

## How the rename works

Each file format is edited with a parser for that format, and only values whose meaning is
certain are changed.

| Where | What changes | What deliberately does not |
|---|---|---|
| **Manifest** (binary XML) | `package`, plus pooled strings equal to the old package or starting with `oldpkg.`: component names, permissions, inline provider authorities | Relative names like `.MainActivity` (they resolve under the new package automatically) |
| **DEX code** (smali/dexlib2) | Class, method, and field type descriptors under the old package (`Lcom/old/pkg/…;`), so `com.old.pkg.Foo` becomes `com.new.pkg.Foo` | String constants, with one exception: a string that **exactly** equals a provider authority that was renamed (e.g. `FileProvider.getUriForFile(ctx, "com.old.pkg.fileprovider", f)`) |
| **Resource XML** (`res/**/*.xml`) | Strings that exactly name a class the DEX step renamed: custom views in layouts, fragments, preference screens | Anything else that merely starts with the package name |
| **`resources.arsc`** | String values of provider authorities declared by reference (`android:authorities="@string/…"`), edited in place in the global string pool | Resource IDs, every other value, styled-string spans. The table stays STORED and 4-byte aligned, as Android 11+ requires |
| **Native libraries** (`lib/<abi>/*.so`) | Nothing. Copied byte-for-byte and page-aligned | ELF binaries are never patched, even if they contain the old name |

URLs, database and preference names, reflection targets, and SDK configuration often
contain the old package name on purpose, so they are left alone. If an app's code lives
under a different namespace from its application ID (common), its class names are left
unchanged too.

## Tested apps

Renamed on a device, installed next to the untouched original, and launched:

| App | Signed by | Notable | Result |
|---|---|---|---|
| GPS Logger | its developer | min SDK 14, FileProvider | Works |
| AntennaPod | its developer | 4 DEX files, native libraries for 4 ABIs, authority defined in `resources.arsc`, 53 custom-view references in layouts | Works (main screens, settings, statistics) |
| Whisper+ | F-Droid | native libraries | Launches |
| Android Easter Egg | Android platform key | min SDK 37, custom views | Works, except one screen that calls platform-only APIs |
| AOSP Camera | Android platform key | native libraries installed outside the APK | Works, including taking and saving a photo |

Each of these exposed a real bug that is now fixed and covered by a unit test. Apps signed
with the platform key lose platform privileges when re-signed, so anything using hidden
platform APIs fails. That is an Android restriction, not something a repackager can fix.

## Known limitations

- **Split APKs:** only the base APK is processed. An app installed as a split set (for
  example with native code in `split_config.arm64_v8a.apk`) will be incomplete after
  renaming. The app warns about this before running.
- **Signature checks:** apps that verify their own certificate, use Play Integrity, or
  depend on Google sign-in, Firebase, or OAuth clients registered to the original
  package/certificate will usually refuse to work.
- **Old devices:** outputs are signed for Android 4.3 (API 18) and newer, even if the
  original app supports older versions.
- **Provider conflicts:** a provider authority that doesn't start with the package name
  (for example a library's fixed authority) is not renamed. If the original is installed,
  Android will refuse the copy, and APK Repacker reports this on the result screen.

## Signing

By default the output is signed with an RSA key generated inside **AndroidKeyStore**
(hardware-backed where available, non-exportable). You can import your own PKCS#12, JKS,
or BKS keystore from the overflow menu. Keys are never transmitted anywhere.

## Security

- Processes everything locally; never uploads APKs or keys; never runs extracted code.
- Treats APK contents as untrusted: ZIP extraction rejects `../` and absolute paths
  (zip-slip) and enforces per-entry and total size limits (decompression bombs).
- Never modifies or overwrites the installed original or files belonging to other apps.
- Working files live in app-private storage and are deleted after a successful run unless
  you tick "Keep intermediate files".

## Toolchain

| Concern | Implementation |
|---|---|
| App discovery and extraction | `PackageManager`, `ApplicationInfo.sourceDir` / `nativeLibraryDir` |
| Binary XML and `resources.arsc` string pools | In-tree `com.apkrepacker.apk.axml` (`AxmlFile`, `ArscFile`), which edit string values in place and keep indices, IDs, and everything else intact |
| DEX rewriting | [smali/dexlib2](https://github.com/google/smali) |
| Rebuild and zipalign | In-tree `ApkBuilder` (STORED entries 4-byte aligned, `.so` 4096-byte aligned) |
| Signing and verification | [apksig](https://android.googlesource.com/platform/tools/apksig/) |

## Project layout

```
app/src/main/java/com/apkrepacker/
  ui/        MainActivity, AppListActivity, ResultActivity, Installer
  apk/       RepackageEngine and its stages: ApkExtractor, ApkAnalyzer, ApkDecoder,
             PackageTransformer, DexTransformer, IconWatermarker, ApkBuilder,
             ApkSigner, ApkVerifier
  apk/axml/  AxmlFile (binary XML), ArscFile (resource table string pool)
  security/  KeystoreManager
  storage/   ApkStorage, SafeZip
testapp/     Small sample app (activities, provider, package name in several contexts)
```

All APK work runs off the UI thread.

## Building

Requires the Android SDK and **JDK 17–21** (Gradle 8.9 rejects newer JDKs, including the
JDK 25 bundled with recent Android Studio releases).

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest   # pipeline tests
./gradlew :testapp:assembleDebug   # sample target app
```

## Tests

`app/src/test/java/com/apkrepacker/apk/RepackagePipelineTest.java` runs the real pipeline
classes on the JVM against a real APK fixture (everything except Android-only glue such as
PackageManager and AndroidKeyStore):

- package-name validation
- manifest package read, rename, and re-parse round trip
- full decode → manifest rewrite → DEX remap → rebuild → sign → verify
- DEX rewriting for apps whose min SDK is newer than dexlib2 knows about
- exact-match class renames in resource XML (prefix matches are left alone)
- v1 signing without SHA-1 for apps with min SDK below 18
- exact-match authority renames in DEX code and static fields
- `resources.arsc` string rename that leaves the rest of the table byte-identical and the
  entry STORED and aligned

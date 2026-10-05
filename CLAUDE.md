Build a native Android application in Java or Kotlin called **APK Repacker**.

The application is intended for legitimate APK inspection, backup, modification, and repackaging of apps the user owns or has permission to modify.

## Core functionality

The app must:

1. Display applications installed on the device.
2. Allow the user to select an installed application.
3. Extract/copy the application's APK from its installed location into the app's private working directory.
4. Allow the user to specify a new Android package/application ID, for example:
   `com.example.modifiedapp`
5. Repackage the APK using the new package identity.
6. Sign the resulting APK with a signing key controlled by this application.
7. Save the resulting APK somewhere the user can access through Android's file picker.
8. Display useful progress, errors, and build logs.

Do not upload APKs or source code to a server. All processing should happen locally on the Android device.

## Important Android distinction

An Android APK can contain several different identifiers. Do not assume that changing the `package` attribute in AndroidManifest.xml is sufficient.

Handle at least:

* Manifest package/application ID
* Application ID used by the APK
* DEX references to the original package
* Manifest component names
* Provider authorities
* Intent/action references where relevant
* Resource/package identifiers where relevant
* Signing information

Do not blindly replace every occurrence of the original package string in binary files. APKs contain binary structures and arbitrary strings where such replacement would corrupt the application.

## APK extraction

Use Android's PackageManager to discover installed applications.

For each selected application:

* Obtain its `ApplicationInfo`.
* Obtain the APK location from `ApplicationInfo.sourceDir`.
* Copy the APK into an application-private working directory.
* Never modify the original installed APK.
* Preserve the original APK as an untouched input artifact.

For applications using split APKs, detect that situation instead of pretending that the base APK is a complete standalone application.

If the selected application has multiple APK splits, clearly tell the user that a complete repackaging operation may require processing the complete split set.

## APK processing pipeline

Implement the operation as a series of explicit stages:

### Stage 1 — Input validation

Validate:

* Input is a valid ZIP/APK.
* AndroidManifest.xml exists.
* APK contains DEX files.
* The requested new package name is syntactically valid.
* The new package name is different from the original package name.
* The output path is writable.

Reject invalid Java/Android package names.

### Stage 2 — Decode APK

Use a local APK-processing implementation/library rather than implementing the Android binary XML and resource formats from scratch.

The pipeline should conceptually perform:

```text
APK
 ↓
decode
 ↓
modify manifest/resources/code where necessary
 ↓
rebuild
 ↓
zipalign
 ↓
sign
 ↓
verify
 ↓
output APK
```

Prefer mature Android APK tooling that can run locally.

Do not require a backend server.

### Stage 3 — Package-name transformation

Determine the original application/package identity automatically.

Allow the user to enter:

```text
Original:
com.example.original

New:
com.example.modified
```

The transformation must account for the fact that Java/Kotlin class names may use the original package.

For example:

```text
com.example.original.MainActivity
```

may need to become:

```text
com.example.modified.MainActivity
```

However, do not perform naive global byte replacement.

Use a parser/rewriter appropriate to the relevant file format.

### Stage 4 — Manifest

Modify the decoded AndroidManifest appropriately.

Pay particular attention to:

* application ID/package identity
* activities
* services
* receivers
* providers
* activity aliases
* provider authorities
* permissions
* metadata
* intent filters

Component names beginning with `.` need special handling because they are relative to the package/application namespace.

For example:

```xml
<activity android:name=".MainActivity"/>
```

must resolve under the new package.

Provider authorities are especially important because they often contain the original package name:

```text
com.example.original.provider
```

Avoid creating collisions with authorities already installed on the device.

### Stage 5 — DEX/code references

If changing the Java/Kotlin namespace is required, process the DEX files using a proper DEX-aware transformation.

Do not treat DEX as plain text.

Handle:

* class descriptors
* method references
* field references
* annotations
* string constants only when semantically appropriate

Be conservative.

Some applications deliberately contain the old package name as configuration data, URLs, database names, preferences, reflection strings, or third-party SDK configuration. Those strings should not automatically be changed.

### Stage 6 — Resources

Preserve Android resource IDs whenever possible.

Do not regenerate resources unnecessarily.

If the APK's resource table contains package-specific information, use APK/resource tooling rather than manually editing binary resource structures.

### Stage 7 — Native libraries

Preserve:

```text
lib/arm64-v8a/
lib/armeabi-v7a/
lib/x86/
lib/x86_64/
```

and other ABI directories exactly unless modification is actually required.

Do not modify ELF binaries simply because they contain the old package name.

Native code may contain hardcoded paths or package names, but those should only be modified through a deliberate native-binary patching feature rather than the normal package rename operation.

## Rebuild

Rebuild the modified APK using the APK tooling.

After rebuilding:

1. Verify the APK is structurally valid.
2. Run zip alignment.
3. Sign the APK.
4. Verify the APK signature.
5. Verify that Android recognizes the resulting package name.

Use a newly generated signing key if the user has not configured one.

The application should preferably generate and securely store its own keystore in Android app-private storage.

Allow an advanced setting for importing a user-provided signing key.

Never transmit private keys anywhere.

## Signing

Clearly explain that the repackaged APK cannot retain the original developer's signature.

The output will be signed by the user's selected signing key.

Support modern Android APK signing where the selected tooling permits it, preferably APK Signature Scheme v2/v3 or newer appropriate to the target Android version.

After signing, run signature verification and show:

```text
Package:
com.example.modifiedapp

Signer:
<certificate information>

APK:
<path/name>

Signature:
VALID
```

## Installation

Do not silently install the resulting APK.

Provide an "Install APK" button that launches Android's normal package installer using a content URI supplied through FileProvider or the appropriate modern Android mechanism.

The user must explicitly approve installation.

Handle Android's "install unknown apps" restrictions normally rather than attempting to bypass them.

## Existing package conflicts

If the original application is still installed, the new package name normally prevents it from replacing the original application.

Before installation, check whether the requested package name is already installed.

If it is, warn the user:

```text
An application with this package name is already installed.
Choose another package name or uninstall the existing application.
```

Do not automatically uninstall applications.

## APK limitations

Display a warning before repackaging:

* Some applications will not work after their package name changes.
* Applications may verify their own signing certificate.
* Google Play services may depend on the original package/certificate.
* OAuth clients may be registered to the original package and signing certificate.
* Firebase configuration may depend on package/certificate identity.
* DRM systems may reject modified applications.
* Native libraries may contain assumptions about the original package.
* Applications using reflection may break after namespace changes.
* Split APKs require special handling.
* Updating an existing application requires the original signing key.

Do not claim that arbitrary APKs can always be successfully renamed.

## User interface

Create a simple Material-style interface.

Main screen:

```text
APK Repacker

[ Select Installed App ]

Selected App:
Example App
com.example.original

Original APK:
example.apk

New Package Name:
[ com.example.modified ]

[ Analyze APK ]
[ Repackage APK ]
```

After analysis, show:

```text
APK Analysis

Package: com.example.original
Version: 1.2.3
Version Code: 123
Min SDK: 26
Target SDK: 35

DEX files: 3
Native libraries: arm64-v8a, armeabi-v7a
Split APK: No

Potential issues:
⚠ Package-specific configuration detected
⚠ Certificate differs from original

[ Continue ]
```

During processing show a stage-based progress display:

```text
✓ Extract APK
✓ Decode APK
✓ Modify package identity
✓ Rebuild APK
✓ Zipalign
✓ Sign APK
✓ Verify APK

Complete
```

## Architecture

Keep the APK processing engine separate from the UI.

Suggested structure:

```text
app/
 ├── ui/
 │    ├── MainActivity
 │    ├── AppListActivity
 │    └── ResultActivity
 │
 ├── apk/
 │    ├── ApkExtractor
 │    ├── ApkAnalyzer
 │    ├── ApkDecoder
 │    ├── PackageTransformer
 │    ├── ApkBuilder
 │    ├── ApkSigner
 │    └── ApkVerifier
 │
 ├── security/
 │    └── KeystoreManager
 │
 └── storage/
      └── ApkStorage
```

Use background processing for APK operations so the UI never freezes.

Show logs in a scrollable diagnostic view.

## Storage

Use Android's Storage Access Framework for user-selected input/output locations where appropriate.

Keep temporary files inside the application's private cache/files directory.

Delete temporary decoded/rebuilt files after successful completion unless the user explicitly chooses to keep them.

Never modify files belonging to another application.

## Error handling

Errors should be specific.

For example:

```text
Repackaging failed.

Stage: Manifest processing

Reason:
The APK contains an unsupported binary manifest structure.

Original APK has not been modified.
```

Do not show a generic "Something went wrong" when a useful diagnostic is available.

## Security requirements

The application must:

* Process APKs locally.
* Never upload APK contents.
* Never upload signing keys.
* Never execute extracted APK code.
* Treat APK contents as untrusted input.
* Prevent path traversal when extracting ZIP entries.
* Reject ZIP entries containing `../`.
* Avoid writing extracted files outside the temporary working directory.
* Limit extraction sizes to prevent decompression-bomb attacks.
* Clean up temporary files.
* Never overwrite the original APK.

## Important implementation goal

Do not attempt to write an APK parser, Android binary XML parser, DEX parser, resource compiler, APK signer, and zipalign implementation from scratch.

Use established open-source Android/APK tooling where licensing permits.

First determine which components can realistically run on Android itself. If a desktop-oriented tool cannot run directly on Android, find an Android-compatible implementation or port only the necessary functionality.

The finished application must not depend on a PC, Termux, shell commands installed by the user, or a remote server for normal operation.

## Testing

Create test APKs specifically for the project.

At minimum test:

1. Simple Java application.
2. Kotlin application.
3. Application with multiple activities.
4. Application with a ContentProvider.
5. Application with services and receivers.
6. Application containing native libraries.
7. Application containing multiple DEX files.
8. Application with resources.
9. Application using relative manifest component names.
10. Application whose original package name appears in several different contexts.

For every test:

```text
original APK
    ↓
repackage
    ↓
install using new package name
    ↓
launch
    ↓
exercise major functionality
    ↓
verify original application remains untouched
```

Also test failure cases deliberately.

The final app should make it obvious which APK was used, what package name was requested, what signing certificate was used, and whether the resulting APK passed structural/signature verification.


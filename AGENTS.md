# APM Repository Agent Rules

## Preserve APKs and real-device app state

- Treat every APK artifact, installed Android package, and its app-private data as user-owned persistent state.
- Never delete, replace, move, or overwrite an existing APK artifact unless the user explicitly authorizes that exact action.
- Before any Gradle task that may package an APK (including connected tests), verify a fresh output directory does not exist and pass `-PapmIsolatedBuildDir=/private/tmp/<unique-build-name>`; never package into the repository's default build directory when it already contains APKs.
- The repository keeps exactly one user-facing deliverable APK at `/Users/jinof/source/apm/android/APM-0.0.2-debug.apk`. After verification, copy the isolated tested APK to this canonical filename; do not create version-, date-, feature-, or test-specific APK names. Replacing this one canonical deliverable is allowed only after the build and relevant tests pass. Keep the isolated tested APK as provenance in its fresh build directory.
- Never run `adb uninstall`, `pm uninstall`, `pm clear`, or any equivalent operation on a user's real device without explicit confirmation that names the package and warns that app-private data may be lost.
- After a requested code change has passed its relevant gates, default to installing the newly verified APK on the user's already-installed real phone with `adb install -r`. This standing instruction authorizes only a same-package, data-preserving update: first identify the intended real-device serial, confirm that `com.jinof.apm` is already installed, record its version and app-private database presence, and verify them again after installation.
- On a real device, default to a data-preserving update with `adb install -r` only after confirming that the package is already installed. If the package is absent, stop and tell the user before attempting a fresh installation.
- Do not run `connectedDebugAndroidTest`, Gradle Managed Device installation workflows, or other test tooling against a user's real phone when the workflow may uninstall, replace, downgrade, or clear the main application package. Use an emulator or an isolated test `applicationId` instead.
- Before any real-device install or test mutation, inspect the current package/version and state the expected effect. Afterward, verify that the package and app data remain present.
- If an install or test deployment is rejected, do not retry with a more destructive installation path. Diagnose the restriction and preserve the existing installation.
- Any unavoidable removal or data reset requires a fresh, action-time confirmation from the user.

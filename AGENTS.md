# Agent Notes

This is an Android app project intended to run from Android Studio on Windows. Prefer the checked-in Gradle wrapper and the Android Studio packaged JBR/SDK instead of assuming system `java`, `gradle`, `adb`, or `emulator` are on `PATH`.

## Local Tooling

- Project root: `C:\Users\anthony\dev\c3p0`
- Android Studio JBR: `C:\Users\anthony\AppData\Local\Programs\Android Studio\jbr`
- Android SDK: `C:\Users\anthony\AppData\Local\Android\Sdk`
- Emulator: `C:\Users\anthony\AppData\Local\Android\Sdk\emulator\emulator.exe`
- ADB: `C:\Users\anthony\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Available AVD: `Pixel_9_Pro_XL`

## Git

Always use the Git installation inside WSL for this repository. Windows Git is not configured for the user's SSH credentials.

When the user asks for code changes and the work is complete, commit and push the changes to `origin/main` unless the user explicitly says not to.

When adding, committing, and pushing changes, update the relevant project docs as part of the same change set. Include those docs in the commit and push so implementation changes and documentation stay together.

From PowerShell, run Git commands through WSL with the repo path translated to `/mnt/c`:

```powershell
wsl git -C /mnt/c/Users/anthony/dev/c3p0 status --short --branch
wsl git -C /mnt/c/Users/anthony/dev/c3p0 push origin main
```

Before running Gradle commands in PowerShell, scope Java to the current command/session:

```powershell
$env:JAVA_HOME = 'C:\Users\anthony\AppData\Local\Programs\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Use the Android Studio SDK tools directly or add them to the current PowerShell session:

```powershell
$env:ANDROID_HOME = 'C:\Users\anthony\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
```

## Build

Run the full build from the repo root:

```powershell
.\gradlew.bat build
```

Useful narrower build commands:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lint
```

## Test

Run JVM/unit tests that do not require a device:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Run all local unit test variants:

```powershell
.\gradlew.bat :app:test
```

Instrumentation tests are not part of the default verification loop. Run them only when the change touches device/emulator-specific behavior, when a failure cannot be covered by unit tests, or when the user explicitly asks for them. When instrumentation is needed, run it only on the `Pixel_9_Pro_XL` emulator. Do not run instrumentation tests on the user's physical phone, even if it is connected over ADB.

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Confirm Build And Test Status

Default verification for routine code changes is the debug unit test task:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Run the debug assemble task when a compile/package check is specifically useful, such as after Gradle, manifest, resource, generated-code, or wiring changes:

```powershell
.\gradlew.bat :app:assembleDebug
```

Run the full build only for broad/risky changes, release/CI validation, or when the user explicitly asks for it:

```powershell
.\gradlew.bat build
```

Run instrumentation tests only for device/emulator-specific behavior or when the user explicitly asks. If instrumentation is needed and the `Pixel_9_Pro_XL` emulator is unavailable, skip instrumentation tests and report that they were skipped because the emulator was unavailable.

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest
```

When reporting verification back to the user, explicitly say which commands passed, which commands were skipped, and why. A successful `:app:testDebugUnitTest` confirms the default unit-test verification. A successful `:app:assembleDebug` confirms the debug app compiles. A successful `build` confirms assemble, local tests, lint/check tasks, and release packaging for this Gradle project.

## Start A Virtual Device

List available Android virtual devices:

```powershell
& 'C:\Users\anthony\AppData\Local\Android\Sdk\emulator\emulator.exe' -list-avds
```

Start the packaged Android Studio virtual device:

```powershell
Start-Process -WindowStyle Hidden -FilePath 'C:\Users\anthony\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd', 'Pixel_9_Pro_XL'
```

Wait for the device to boot:

```powershell
& 'C:\Users\anthony\AppData\Local\Android\Sdk\platform-tools\adb.exe' wait-for-device
& 'C:\Users\anthony\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell getprop sys.boot_completed
```

When `sys.boot_completed` returns `1`, install or test against the emulator:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:installDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Run The App

Run from Android Studio when possible: open the repo at `C:\Users\anthony\dev\c3p0`, select the `app` run configuration, select the `Pixel_9_Pro_XL` device, and press Run.

To run from PowerShell, use the packaged Android Studio runtime and SDK paths:

```powershell
$env:JAVA_HOME = 'C:\Users\anthony\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\anthony\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
```

Start the emulator if needed:

```powershell
Start-Process -WindowStyle Hidden -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd', 'Pixel_9_Pro_XL'
& "$env:ANDROID_HOME\platform-tools\adb.exe" wait-for-device
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell getprop sys.boot_completed
```

After `sys.boot_completed` returns `1`, install and launch the debug app:

```powershell
.\gradlew.bat :app:installDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n dev.whileloop.c3p0/.MainActivity
```

The application id is `dev.whileloop.c3p0`.

## Notes For Future Agents

- Do not edit `local.properties`; Android Studio generates it and it is machine-specific.
- Always use WSL Git for status, diff, commit, fetch, pull, and push.
- If `java` is missing or `JAVA_HOME` is malformed, use the Android Studio JBR path above for the current shell.
- Do not start the emulator or run connected tests by default. Unit tests are usually enough for routine cycles.
- If emulator commands are explicitly needed and fail because no device is running, start `Pixel_9_Pro_XL` first and wait for boot completion before running connected tests.
- Never run instrumentation tests on a physical Android device. Pin `ANDROID_SERIAL` to the `Pixel_9_Pro_XL` emulator serial before `:app:connectedDebugAndroidTest`, and skip connected tests if that emulator is not available.
- Keep build/test fixes scoped to the Android Gradle project unless the user explicitly asks for wider cleanup.

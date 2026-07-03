# Repository Guidelines

## Project Overview
- This is an Android application project named `C3P0`, a companion app for WalkingPad (C2) and Garmin (Venu 3).
- The app module lives in `app/` with namespace/application ID `dev.whileloop.c3p0`.
- Build configuration uses Kotlin DSL Gradle files and a version catalog in `gradle/libs.versions.toml`.

## Core Goals
- **WalkingPad Control**: BLE connection to control and monitor WalkingPad C2.
- **Garmin Integration**: BLE connection to Garmin Venu 3 for HR monitoring.
- **Auto-Speed (Zone 2)**: Adjust treadmill speed based on heart rate.
- **Health Connect**: Export sessions and calculate "Normalized Steps" (excluding overlapping data).
- **Cloud Sync**: Google Drive integration for state backup/sync (no backend).
- **Architecture**: Shared BLE controller, Room DB, Jetpack Compose, Clean Architecture.

## Code Style
- Follow Kotlin official style (`kotlin.code.style=official`).
- Keep changes minimal and consistent with existing Android/Jetpack Compose patterns.
- Prefer clear, descriptive names; avoid one-letter variables except for conventional short-lived lambda parameters.
- Do not add license headers or broad refactors unless explicitly requested.

## Android Guidelines
- Put app code under `app/src/main/` using the standard Android source layout.
- Keep resources in the appropriate `res/` subdirectories and use existing naming conventions.
- When adding Compose UI, prefer Material 3 components already declared in the catalog.
- Keep package names aligned with `dev.whileloop.c3p0`.

## Dependencies
- Manage dependency versions in `gradle/libs.versions.toml`.
- Use catalog aliases from Gradle build files instead of hard-coded dependency strings.
- Do not change Android Gradle Plugin, Kotlin, SDK, or Compose BOM versions unless the task requires it.

## Validation
- For build verification, prefer `./gradlew assembleDebug`.
- For unit tests, use `./gradlew testDebugUnitTest` when tests exist or are added.
- For instrumentation tests, use `./gradlew connectedDebugAndroidTest` only when a device/emulator is available.
- If validation cannot run due to missing SDK, network, or device constraints, report the exact blocker.

## Project Tracking
- This repository serves as the primary project tracker.
- Roadmap, tasks, and implementation plans are located in the `roadmap/` directory.
- Use `roadmap/GOALS.md` for high-level objectives and `roadmap/TASKS.md` for detailed task tracking.
- **Agents MUST update these files consistently** to reflect current progress, new findings, and completed tasks.
- Persist finalized implementation plans to `roadmap/plans/` for historical reference and context for future agents.

## Git Hygiene
- Do not commit, create branches, or rewrite history unless explicitly asked.
- Do not modify unrelated files or generated build output.
